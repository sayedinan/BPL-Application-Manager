package com.bpl.orderapp.admin.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * The open WebSockets, so they can be cut off when access ends. Topics are only
 * checked at SUBSCRIBE time, so without this a socket would keep receiving data
 * after logout, deletion, a password reset or a change of role or assignments.
 * A client that is still allowed in reconnects and is checked again.
 */
@Component
public class LiveSocketRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveSocketRegistry.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();

    public LiveSocketRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    void add(WebSocketSession socket) {
        sockets.put(socket.getId(), socket);
    }

    void remove(String socketId) {
        sockets.remove(socketId);
    }

    /** Closes every socket the user has open. Does nothing for a null username. */
    public void closeForUser(String username) {
        if (username == null) return;
        closeWhere(s -> s.getPrincipal() != null && username.equals(s.getPrincipal().getName()));
    }

    /** Closes the sockets opened by one login, leaving the user's other logins alone. */
    public void closeForHttpSession(String httpSessionId) {
        closeWhere(s -> httpSessionId.equals(httpSessionIdOf(s)));
    }

    /** Catches logins that ended without a call here, such as idle or absolute expiry. */
    @Scheduled(fixedDelayString = "${websocket.session-check-ms:30000}")
    public void closeOrphaned() {
        closeWhere(s -> {
            String id = httpSessionIdOf(s);
            return id == null || sessions.findById(id) == null;
        });
    }

    private static String httpSessionIdOf(WebSocketSession socket) {
        return (String) socket.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
    }

    private void closeWhere(Predicate<WebSocketSession> match) {
        for (WebSocketSession socket : sockets.values()) {
            try {
                if (!match.test(socket)) continue;
                sockets.remove(socket.getId());
                socket.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not check or close WebSocket {}", socket.getId(), e);
            }
        }
    }
}
