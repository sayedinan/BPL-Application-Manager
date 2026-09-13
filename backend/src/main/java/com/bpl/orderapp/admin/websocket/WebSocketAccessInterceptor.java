package com.bpl.orderapp.admin.websocket;

import com.bpl.orderapp.admin.common.Role;
import com.bpl.orderapp.admin.log.WebSocketLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketAccessInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAccessInterceptor.class);
    private final JdbcTemplate jdbc;
    private final AtomicInteger globalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUserConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> perApplicationSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> subscriptionTopics = new ConcurrentHashMap<>();

    public WebSocketAccessInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @jakarta.annotation.PostConstruct
    public void clearStaleStateOnStartup() {
        int subs = jdbc.update("DELETE FROM websocket_subscriptions");
        int conns = jdbc.update("DELETE FROM websocket_connections");
        if (subs > 0 || conns > 0) {
            log.info("Cleared {} stale websocket_subscriptions and {} stale websocket_connections rows on startup", subs, conns);
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        switch (accessor.getCommand()) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            case DISCONNECT -> handleDisconnect(accessor);
            case UNSUBSCRIBE -> handleUnsubscribe(accessor);
            default -> {}
        }
        return message;
    }

    @Transactional
    protected void handleConnect(StompHeaderAccessor accessor) {
        String username = requireUsername(accessor);
        String sessionId = accessor.getSessionId();
        if (globalConnections.get() >= WebSocketLimits.MAX_GLOBAL) throw new MessageDeliveryException("Global connection limit reached");
        AtomicInteger userCount = perUserConnections.computeIfAbsent(username, k -> new AtomicInteger(0));
        if (userCount.get() >= WebSocketLimits.MAX_PER_USER) throw new MessageDeliveryException("Per-user connection limit reached");
        globalConnections.incrementAndGet(); userCount.incrementAndGet(); sessionUsers.put(sessionId, username);
        jdbc.update("INSERT INTO websocket_connections (session_id, username) VALUES (?, ?)", sessionId, username);
    }

    @Transactional
    protected void handleSubscribe(StompHeaderAccessor accessor) {
        String username = requireUsername(accessor);
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (destination == null) throw new MessageDeliveryException("Missing destination");
        Role role = lookupRole(username);
        if ("/topic/audit-log".equals(destination)) {
            if (role != Role.SYS_ADMIN && role != Role.ADMIN) throw new MessageDeliveryException("Not authorized for audit log");
            jdbc.update("INSERT INTO websocket_subscriptions (session_id, subscription_id, topic_key) VALUES (?, ?, ?)", sessionId, subscriptionId, "audit-log");
            return;
        }
        if (destination.startsWith("/topic/application-logs/")) {
            String idPart = destination.substring("/topic/application-logs/".length());
            Long applicationId; try { applicationId = Long.valueOf(idPart); } catch (NumberFormatException e) { throw new MessageDeliveryException("Invalid application id"); }
            if (role != Role.SYS_ADMIN && role != Role.ADMIN) {
                Long userId = lookupUserId(username);
                List<Long> assigned = jdbc.queryForList("SELECT application_id FROM user_application_assignments WHERE user_id = ?", Long.class, userId);
                if (!assigned.contains(applicationId)) throw new MessageDeliveryException("Not authorized for this application's logs");
            }
            Integer appCount = jdbc.queryForObject("SELECT COUNT(*) FROM websocket_subscriptions WHERE topic_key = ?", Integer.class, idPart);
            if (appCount != null && appCount >= WebSocketLimits.MAX_PER_APP) throw new MessageDeliveryException("Per-application subscriber limit reached");
            jdbc.update("INSERT INTO websocket_subscriptions (session_id, subscription_id, topic_key) VALUES (?, ?, ?)", sessionId, subscriptionId, idPart);
            return;
        }
        throw new MessageDeliveryException("Unknown topic: " + destination);
    }

    @Transactional
    protected void handleUnsubscribe(StompHeaderAccessor accessor) {
        jdbc.update("DELETE FROM websocket_subscriptions WHERE session_id = ? AND subscription_id = ?", accessor.getSessionId(), accessor.getSubscriptionId());
    }

    @Transactional
    protected void handleDisconnect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        jdbc.update("DELETE FROM websocket_subscriptions WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM websocket_connections WHERE session_id = ?", sessionId);
    }

    private String requireUsername(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null || accessor.getUser().getName() == null) throw new MessageDeliveryException("No authenticated principal");
        return accessor.getUser().getName();
    }

    private Role lookupRole(String username) {
        List<String> rows = jdbc.queryForList("SELECT role FROM users WHERE username = ? AND deleted_at IS NULL", String.class, username);
        if (rows.isEmpty()) throw new MessageDeliveryException("User not found or deleted");
        return Role.valueOf(rows.get(0));
    }

    private Long lookupUserId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ? AND deleted_at IS NULL", Long.class, username);
    }
}
