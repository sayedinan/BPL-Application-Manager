package com.bpl.orderapp.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF protection. Every state-changing request must carry {@value #HEADER}.
 *
 * <p>{@code SameSite=Strict} alone is not enough: "same site" ignores the port and
 * includes sibling subdomains. A page on another origin cannot add a custom header
 * without a CORS preflight, and this backend approves none. Only the header's
 * presence matters; it is not a secret. Do not enable CORS for other origins
 * without replacing this.
 */
public class CsrfHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-BPL-CSRF";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final String REJECTION_BODY = """
        {"type":"https://errors.bpl-orderapp.com/CSRF_REJECTED","title":"Csrf rejected","status":403,\
        "detail":"The request is missing the X-BPL-CSRF header. Reload the page and try again.",\
        "code":"CSRF_REJECTED"}""";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && request.getHeader(HEADER) == null) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.getWriter().write(REJECTION_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
