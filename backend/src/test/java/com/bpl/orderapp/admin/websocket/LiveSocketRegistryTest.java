package com.bpl.orderapp.admin.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/** Sockets whose login has ended without anyone saying so (idle or absolute expiry). */
@SuppressWarnings("unchecked")
class LiveSocketRegistryTest {

    private final FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);
    private final LiveSocketRegistry registry = new LiveSocketRegistry(sessions);

    private WebSocketSession socket(String id, String httpSessionId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(
            Map.of(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME, httpSessionId));
        registry.add(socket);
        return socket;
    }

    @Test
    void theSweepClosesOnlySocketsWhoseSessionIsGone() throws Exception {
        WebSocketSession live = socket("ws-live", "alive");
        WebSocketSession orphan = socket("ws-orphan", "expired");
        Session alive = mock(Session.class);
        when(sessions.findById("alive")).thenReturn(alive);

        registry.closeOrphaned();

        verify(orphan).close(CloseStatus.POLICY_VIOLATION);
        verify(live, never()).close(any());
    }

    @Test
    void aRemovedSocketIsForgotten() throws Exception {
        WebSocketSession socket = socket("ws-1", "expired");
        registry.remove("ws-1");

        registry.closeOrphaned();

        verify(socket, never()).close(any());
    }
}
