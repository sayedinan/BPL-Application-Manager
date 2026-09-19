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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.bpl.orderapp.admin.audit.AuditWriter;

@Component
public class WebSocketAccessInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAccessInterceptor.class);
    private final JdbcTemplate jdbc;
    private final AtomicInteger globalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUserConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> perApplicationSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> subscriptionTopics = new ConcurrentHashMap<>();

    private static final long CLOSE_GRACE_SECONDS = 30;
    private final AuditWriter auditWriter;
    private final java.util.concurrent.ScheduledExecutorService closeScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-close-detector");
            t.setDaemon(true);
            return t;
        });

    public WebSocketAccessInterceptor(JdbcTemplate jdbc, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

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
            case DISCONNECT -> cleanupSession(accessor.getSessionId());
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
        if ("/topic/application-status".equals(destination)) {
            jdbc.update("INSERT INTO websocket_subscriptions (session_id, subscription_id, topic_key) VALUES (?, ?, ?)", sessionId, subscriptionId, "application-status");
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
    protected void cleanupSession(String sessionId) {
        if (sessionId == null) return;
        jdbc.update("DELETE FROM websocket_subscriptions WHERE session_id = ?", sessionId);
        int deleted = jdbc.update("DELETE FROM websocket_connections WHERE session_id = ?", sessionId);
        String username = sessionUsers.remove(sessionId);
        if (deleted > 0) {
            globalConnections.updateAndGet(v -> Math.max(0, v - 1));
            if (username != null) {
                AtomicInteger userCount = perUserConnections.get(username);
                if (userCount != null) {
                    int remaining = userCount.updateAndGet(v -> Math.max(0, v - 1));
                    if (remaining == 0) scheduleBrowserClosedCheck(username, java.time.Instant.now());
                }
            }
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        cleanupSession(event.getSessionId());
    }

    // The user's last live connection dropped. Wait a grace period (covers page
    // refresh / reconnect), then log LOGOUT at the disconnect time if they are
    // still gone AND still have a live session (a manual logout already removed it).
    private void scheduleBrowserClosedCheck(String username, java.time.Instant disconnectedAt) {
        closeScheduler.schedule(() -> {
            try {
                recordBrowserClosed(username, disconnectedAt);
            } catch (Exception e) {
                log.warn("Browser-closed check failed for '{}'", username, e);
            }
        }, CLOSE_GRACE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void recordBrowserClosed(String username, java.time.Instant disconnectedAt) {
        AtomicInteger current = perUserConnections.get(username);
        if (current != null && current.get() > 0) return; // reconnected or another tab open
        Integer sessions = jdbc.queryForObject(
            "SELECT COUNT(*) FROM spring_session WHERE principal_name = ? AND expiry_time > ?",
            Integer.class, username, System.currentTimeMillis());
        if (sessions == null || sessions == 0) return; // already logged out / expired
        List<String> roles = jdbc.queryForList(
            "SELECT role FROM users WHERE username = ? AND deleted_at IS NULL", String.class, username);
        String role = roles.isEmpty() ? "UNKNOWN" : roles.get(0);
        auditWriter.writeAt(disconnectedAt, "LOGOUT", username, role, Map.of("reason", "BROWSER_CLOSED"), "SUCCESS");
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", username);
        log.info("Browser closed detected for '{}' at {}; LOGOUT audited, orphaned session removed", username, disconnectedAt);
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
