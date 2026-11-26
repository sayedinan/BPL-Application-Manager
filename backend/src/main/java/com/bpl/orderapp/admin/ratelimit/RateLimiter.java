package com.bpl.orderapp.admin.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One token bucket per key, held in memory (single backend instance, SPEC §1).
 * The number of keys is capped so that made-up usernames or addresses cannot
 * grow the map without bound; the least recently used bucket is dropped first.
 */
public class RateLimiter {

    private static final int MAX_KEYS = 10_000;

    private final long capacity;
    private final Duration period;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /** Allows bursts of {@code capacity}, refilled evenly over {@code period}. */
    public RateLimiter(long capacity, Duration period) {
        this.capacity = capacity;
        this.period = period;
    }

    /** Takes one token. Returns 0 if allowed, otherwise the whole seconds to wait. */
    public long tryAcquire(String key) {
        ConsumptionProbe probe = bucket(key).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) return 0;
        return Math.max(1, Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
    }

    public synchronized void reset(String key) {
        buckets.remove(key);
    }

    private synchronized Bucket bucket(String key) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
            .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
            .build());
    }
}
