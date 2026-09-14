-- V9 — add source_ip to audit_logs.
--
-- Nullable and with no backfill: existing rows predate IP capture
-- and there's no way to reconstruct their origin, so they correctly
-- read NULL rather than a fabricated value. Stored as VARCHAR(64)
-- rather than INET because X-Forwarded-For can legitimately contain
-- a comma-separated proxy chain in edge cases, and VARCHAR keeps
-- the write path simple; INET would reject anything that isn't
-- a bare valid address.
ALTER TABLE audit_logs ADD COLUMN source_ip VARCHAR(64);
