package com.bpl.orderapp.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteSessionLifetimeFilterTest {

    private static MockHttpSession run(Duration sessionAge, boolean withSession) throws Exception {
        MockHttpSession session = new MockHttpSession();
        // The session was created "now"; the filter's clock is sessionAge later.
        Clock later = Clock.fixed(Instant.ofEpochMilli(session.getCreationTime()).plus(sessionAge), ZoneOffset.UTC);
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (withSession) request.setSession(session);
        new AbsoluteSessionLifetimeFilter(later)
            .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return session;
    }

    @Test
    void sessionYoungerThanTheCap_isLeftAlone() throws Exception {
        assertThat(run(Duration.ofDays(89), true).isInvalid()).isFalse();
    }

    @Test
    void sessionOlderThanTheCap_isInvalidated_evenIfRecentlyUsed() throws Exception {
        assertThat(run(Duration.ofDays(91), true).isInvalid()).isTrue();
    }

    @Test
    void requestWithoutASession_passesThroughWithoutCreatingOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();
        new AbsoluteSessionLifetimeFilter().doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(request.getSession(false)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }
}
