-- V22 - keep CPU, memory and disk in the health history.
--
-- Until now application_health_history (V20) held only whether each check
-- worked, how long it took, and the status. These three columns keep the
-- resource readings of every successful check too, so the details page can
-- chart them over hours or days, not just the last few minutes it has watched
-- live. Percentages, 0 to 100, as in HEALTH_CONTRACT.md.
--
-- NULL means "not provided": a failed check, or an application that does not
-- report that resource. Rows written before this migration stay NULL, so the
-- charts start filling from the moment this is deployed.
--
-- Retention is unchanged (30 days, HEALTH-MONITORING.md §4); there is still
-- one row per check.

ALTER TABLE application_health_history
    ADD COLUMN cpu_percent    REAL CHECK (cpu_percent    BETWEEN 0 AND 100),
    ADD COLUMN memory_percent REAL CHECK (memory_percent BETWEEN 0 AND 100),
    ADD COLUMN disk_percent   REAL CHECK (disk_percent   BETWEEN 0 AND 100);
