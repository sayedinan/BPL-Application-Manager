package com.bpl.orderapp.admin.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately NOT @Transactional: after a unique-index violation the
 * transaction should not be reused (it was unusable on the former
 * PostgreSQL setup and is rolled back here too), so each repository call runs
 * in its own short transaction. The unique index is the single source
 * of truth, which also makes concurrent identical requests race-safe.
 */
@Service
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyKeyRepository repo;

    public IdempotencyService(IdempotencyKeyRepository repo) { this.repo = repo; }

    public void checkAndStore(String key, UUID userId, Long appId) {
        if (key == null || key.isBlank()) {
            return; // no key supplied -> no idempotency protection (decision A)
        }
        Instant now = Instant.now();
        repo.deleteExpired(now);
        try {
            repo.saveAndFlush(new IdempotencyKey(key, userId, appId, now, now.plus(TTL)));
        } catch (DataIntegrityViolationException e) {
            // Only a real duplicate is a conflict; an FK failure is not.
            if (repo.existsByUserIdAndApplicationIdAndIdempotencyKey(userId, appId, key)) {
                throw new IdempotencyConflictException();
            }
            throw e;
        }
    }
}