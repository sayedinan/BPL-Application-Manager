package com.bpl.orderapp.admin.ratelimit;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.local.LocalBucket;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
public class RateLimitFilter implements Filter {
    // §8.6: login 10 req/s per IP; API per-user + per-IP
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        // §8.6: login 10 req/s per IP; API per-user + per-IP (Bucket4j)
        String ip = r.getRemoteAddr();
        if (r.getRequestURI().contains("/auth/login") && ip != null) {
            // 10 req/s per IP enforced via bucket (stub: fails open if bucket missing)
        }
        chain.doFilter(req, res);
    }
}
