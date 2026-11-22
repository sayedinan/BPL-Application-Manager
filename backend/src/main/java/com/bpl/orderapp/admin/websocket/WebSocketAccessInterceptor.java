package com.bpl.orderapp.admin.websocket;

import com.bpl.orderapp.admin.common.Role;
import com.bpl.orderapp.admin.log.WebSocketLimits;
import com.bpl.orderapp.admin.security.SessionConfig;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserApplicationAssignmentRepository;
import com.bpl.orderapp.admin.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.log.LogViewerEvent;

@Component
public class WebSocketAccessInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAccessInterceptor.class);
    private final FindByIndexNameSessionRepository<?> sessionRepository;
    private final UserRepository userRepository;
    private final UserApplicationAssignmentRepository assignmentRepository;
    private final WebSocketConnectionRepository connectionRepository;
    private final WebSocketSubscriptionRepository subscriptionRepository;
    private final AtomicInteger globalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUserConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> perApplicationSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> subscriptionTopics = new ConcurrentHashMap<>();

    private static final long CLOSE_GRACE_SECONDS = 30;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher events;
    private final java.util.concurrent.ScheduledExecutorService closeScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-close-detector");
            t.setDaemon(true);
            return t;
        });

    public WebSocketAccessInterceptor(FindByIndexNameSessionRepository<?> sessionRepository, AuditWriter auditWriter,
                                      ApplicationEventPublisher events, UserRepository userRepository,
                                      UserApplicationAssignmentRepository assignmentRepository,
                                      WebSocketConnectionRepository connectionRepository,
                                      WebSocketSubscriptionRepository subscriptionRepository) {
        this.connectionRepository = connectionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.sessionRepository = sessionRepository;
        this.auditWriter = auditWriter;
        this.events = events;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @jakarta.annotation.PostConstruct
    public void clearStaleStateOnStartup() {
        long subs = subscriptionRepository.count();
        long conns = connectionRepository.count();
        subscriptionRepository.deleteAllInBatch();
        connectionRepository.deleteAllInBatch();
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
        connectionRepository.save(new WebSocketConnection(sessionId, username));
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
            subscriptionRepository.save(new WebSocketSubscription(sessionId, subscriptionId, "audit-log"));
            return;
        }
        if (destination.startsWith("/topic/application-logs/")) {
            String idPart = destination.substring("/topic/application-logs/".length());
            Long applicationId; try { applicationId = Long.valueOf(idPart); } catch (NumberFormatException e) { throw new MessageDeliveryException("Invalid application id"); }
            if (role != Role.SYS_ADMIN && role != Role.ADMIN) {
                java.util.UUID userId = lookupUserId(username);
                List<Long> assigned = assignmentRepository.findApplicationIdsByUserId(userId);
                if (!assigned.contains(applicationId)) throw new MessageDeliveryException("Not authorized for this application's logs");
            }
            long appCount = subscriptionRepository.countByTopicKey(idPart);
            if (appCount >= WebSocketLimits.MAX_PER_APP) throw new MessageDeliveryException("Per-application subscriber limit reached");
            subscriptionRepository.save(new WebSocketSubscription(sessionId, subscriptionId, idPart));
            events.publishEvent(new LogViewerEvent(applicationId, true));
            return;
        }
        if (destination.startsWith("/topic/application-health/")) {
            requireApplicationTopicAccess(destination.substring("/topic/application-health/".length()), role, username);
            // Not tracked in websocket_subscriptions: nothing depends on whether anyone is listening.
            return;
        }
        if (destination.startsWith("/topic/application-status/")) {
            requireApplicationTopicAccess(destination.substring("/topic/application-status/".length()), role, username);
            return;
        }
        throw new MessageDeliveryException("Unknown topic: " + destination);
    }

    @Transactional
    protected void handleUnsubscribe(StompHeaderAccessor accessor) {
        List<String> keys = subscriptionRepository.findTopicKeys(accessor.getSessionId(), accessor.getSubscriptionId());
        subscriptionRepository.deleteAllBySubscription(accessor.getSessionId(), accessor.getSubscriptionId());
        publishViewersLeft(keys);
    }

    @Transactional
    protected void cleanupSession(String sessionId) {
        if (sessionId == null) return;
        List<String> keys = subscriptionRepository.findTopicKeysBySession(sessionId);
        subscriptionRepository.deleteAllBySession(sessionId);
        publishViewersLeft(keys);
        int deleted = connectionRepository.deleteAllBySession(sessionId);
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

    void recordBrowserClosed(String username, java.time.Instant disconnectedAt) {
        AtomicInteger current = perUserConnections.get(username);
        if (current != null && current.get() > 0) return; // reconnected or another tab open
        // "Remember me" sessions are meant to outlive the browser, so they are
        // neither audited as a logout nor deleted here (they expire on their own
        // 30-day idle timeout, or on an explicit logout).
        List<String> closableIds = new java.util.ArrayList<>();
        for (var entry : sessionRepository.findByPrincipalName(username).entrySet()) {
            var s = entry.getValue();
            if (s.isExpired()) continue;
            if (Boolean.TRUE.equals(s.getAttribute(SessionConfig.REMEMBER_ME_SESSION_ATTR))) continue;
            closableIds.add(entry.getKey());
        }
        if (closableIds.isEmpty()) return; // already logged out / expired / remember-me only
        String role = userRepository.findByUsername(username).map(User::getRole).orElse("UNKNOWN");
        auditWriter.writeAt(disconnectedAt, "LOGOUT", username, role, Map.of("reason", "BROWSER_CLOSED"), "SUCCESS");
        closableIds.forEach(sessionRepository::deleteById);
        log.info("Browser closed detected for '{}' at {}; LOGOUT audited, orphaned session removed", username, disconnectedAt);
    }

    private void publishViewersLeft(List<String> topicKeys) {
        for (String key : topicKeys) {
            try {
                events.publishEvent(new LogViewerEvent(Long.valueOf(key), false));
            } catch (NumberFormatException ignored) {
                // "audit-log" and other non-application topics
            }
        }
    }

    private String requireUsername(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null || accessor.getUser().getName() == null) throw new MessageDeliveryException("No authenticated principal");
        return accessor.getUser().getName();
    }

    // Shared by the per-application topics (health, status). Same rule as the
    // REST endpoints, decided by Rbac: a USER needs the assignment, ADMIN and
    // SYS_ADMIN may follow every application.
    private void requireApplicationTopicAccess(String idPart, Role role, String username) {
        Long applicationId;
        try {
            applicationId = Long.valueOf(idPart);
        } catch (NumberFormatException e) {
            throw new MessageDeliveryException("Invalid application id");
        }
        if (!com.bpl.orderapp.admin.security.Rbac.canAccess(role, com.bpl.orderapp.admin.security.Action.LIST_APPLICATIONS)) {
            throw new MessageDeliveryException("Not authorized for application updates");
        }
        List<Long> assigned = role == Role.USER
            ? assignmentRepository.findApplicationIdsByUserId(lookupUserId(username))
            : List.of();
        if (!com.bpl.orderapp.admin.security.Rbac.canAccessApplication(role, assigned, applicationId)) {
            throw new MessageDeliveryException("Not authorized for this application");
        }
    }

    private Role lookupRole(String username) {
        return userRepository.findByUsername(username)
            .map(u -> Role.valueOf(u.getRole()))
            .orElseThrow(() -> new MessageDeliveryException("User not found or deleted"));
    }

    private java.util.UUID lookupUserId(String username) {
        return userRepository.findByUsername(username)
            .map(User::getId)
            .orElseThrow(() -> new MessageDeliveryException("User not found or deleted"));
    }
}
