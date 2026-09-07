# Flyway migrations

This directory is intentionally empty for now. Per SPEC §3, the schema is
forward-only and managed by Flyway. Initial migrations will be added
under names matching the SPEC's tables:

- V1: `users`, `applications`, `user_application_assignments`
- V2: `audit_logs`
- V3: `application_log_lines`
- V4: `idempotency_keys`
- V5: `spring_session` (Spring Session JDBC schema)

Do not add ad-hoc DDL here — the SPEC §3 DDL is the single source of truth.
