package com.bpl.orderapp.admin.ratelimit;
// §8.6: Login 10 req/s per IP (Caddy / Bucket4j); API per-user + per-IP
// Configured via Bucket4j; not fully wired to controller until integration step.
public class RateLimitConfig { }
