package com.bpl.orderapp.admin.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.security.SessionConfig;
import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserApplicationAssignmentRepository;
import com.bpl.orderapp.admin.user.UserRepository;

/**
 * The browser-closed detector must clean up ordinary sessions but leave
 * "remember me" sessions alone, otherwise a persistent cookie would point
 * at a deleted session after the browser is closed.
 */
@SuppressWarnings("unchecked")
class WebSocketAccessInterceptorBrowserClosedTest {

    private FindByIndexNameSessionRepository<Session> sessions;
    private AuditWriter audit;
    private UserRepository users;
    private WebSocketAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessions = mock(FindByIndexNameSessionRepository.class);
        audit = mock(AuditWriter.class);
        users = mock(UserRepository.class);
        interceptor = new WebSocketAccessInterceptor(
            sessions, audit, mock(ApplicationEventPublisher.class), users,
            mock(UserApplicationAssignmentRepository.class),
            mock(WebSocketConnectionRepository.class),
            mock(WebSocketSubscriptionRepository.class));
        User u = new User();
        u.setRole("ADMIN");
        when(users.findByUsername("alice")).thenReturn(Optional.of(u));
    }

    private static Session session(boolean expired, boolean rememberMe) {
        Session s = mock(Session.class);
        when(s.isExpired()).thenReturn(expired);
        when(s.getAttribute(SessionConfig.REMEMBER_ME_SESSION_ATTR))
            .thenReturn(rememberMe ? Boolean.TRUE : null);
        return s;
    }

    @Test
    void rememberMeSession_isNotAuditedOrDeleted() {
        Session remembered = session(false, true);
        when(sessions.findByPrincipalName("alice")).thenReturn(Map.of("remembered", remembered));

        interceptor.recordBrowserClosed("alice", Instant.now());

        verify(audit, never()).writeAt(any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(sessions, never()).deleteById(anyString());
    }

    @Test
    void normalSession_isAuditedAndDeleted() {
        Session normal = session(false, false);
        when(sessions.findByPrincipalName("alice")).thenReturn(Map.of("normal", normal));

        interceptor.recordBrowserClosed("alice", Instant.now());

        verify(audit).writeAt(any(), eq("LOGOUT"), eq("alice"), eq("ADMIN"), any(), eq("SUCCESS"));
        verify(sessions).deleteById("normal");
    }

    @Test
    void mixedSessions_deleteOnlyTheNonRememberMeOne() {
        Session remembered = session(false, true);
        Session normal = session(false, false);
        when(sessions.findByPrincipalName("alice")).thenReturn(Map.of(
            "remembered", remembered,
            "normal", normal));

        interceptor.recordBrowserClosed("alice", Instant.now());

        verify(sessions).deleteById("normal");
        verify(sessions, never()).deleteById("remembered");
    }
}
