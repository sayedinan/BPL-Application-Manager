
package com.bpl.orderapp.admin.common;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

@Service

public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void checkAndStore(String key, Long userId, Long appId) {

    jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < NOW()");

    int dup = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE user_id=? AND application_id=? AND idempotency_key=? AND expires_at>NOW()", Integer.class, userId, appId, key);

    if (dup > 0) throw new RuntimeException("IDEMPOTENCY_CONFLICT");

    jdbc.update("INSERT INTO idempotency_keys (idempotency_key,user_id,application_id,created_at,expires_at) VALUES (?,?,?,NOW(),NOW()+INTERVAL '24 hours')", key, userId, appId);

    }

}

