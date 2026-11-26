package com.bpl.orderapp.admin.security;

import com.bpl.orderapp.admin.user.User;
import com.bpl.orderapp.admin.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * SPEC §8.1: while an account must change its password, its session may only do
 * that (and see who it is, or sign out). This covers the WebSocket handshake too.
 * The frontend redirect is a convenience; this is the lock.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/auth/me",
        "/api/v1/auth/change-password",
        "/api/v1/auth/logout",
        "/actuator/health");

    private static final String REJECTION_BODY = """
        {"type":"https://errors.bpl-orderapp.com/PASSWORD_CHANGE_REQUIRED","title":"Password change required",\
        "status":403,"detail":"Change your password before continuing.","code":"PASSWORD_CHANGE_REQUIRED"}""";

    private final UserRepository userRepository;

    public PasswordChangeRequiredFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && !ALLOWED_PATHS.contains(UrlPathHelper.defaultInstance.getPathWithinApplication(request))
                && userRepository.findByUsername(auth.getName()).map(User::isMustChangePassword).orElse(false)) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.getWriter().write(REJECTION_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
