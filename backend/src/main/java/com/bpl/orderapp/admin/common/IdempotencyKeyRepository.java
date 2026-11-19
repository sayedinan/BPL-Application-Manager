package com.bpl.orderapp.admin.common;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    @Transactional
    @Modifying
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    boolean existsByUserIdAndApplicationIdAndIdempotencyKey(
            UUID userId, Long applicationId, String idempotencyKey);
}