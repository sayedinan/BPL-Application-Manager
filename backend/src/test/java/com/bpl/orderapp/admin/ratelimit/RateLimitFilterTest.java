package com.bpl.orderapp.admin.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(new ClientAddress(false), 10, 100, 2);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private int status(String user, String address) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/applications");
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void aSignedInUserHasOneLimitAcrossAddresses_andOtherUsersAreUnaffected() throws Exception {
        assertThat(status("alice", "198.51.100.1")).isEqualTo(200);
        assertThat(status("alice", "198.51.100.2")).isEqualTo(200);

        assertThat(status("alice", "198.51.100.3")).isEqualTo(429);
        assertThat(status("bob", "198.51.100.1")).isEqualTo(200);
    }
}
