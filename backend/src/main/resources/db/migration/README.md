# Flyway migrations (SQLite)

The database is a single SQLite file. `V1__sqlite_baseline.sql` is the whole
schema as of the PostgreSQL -> SQLite migration; it replaces the old
PostgreSQL migrations V1-V27, which live in `../migration-postgres-archive/`
(not on the Flyway classpath, kept for reference and in the `pre-sqlite` tag).

Rules for new migrations (start at `V2__...`):

- Forward-only, additive. Never edit an applied migration.
- SQLite cannot `ALTER COLUMN`, add/drop constraints, or change a CHECK in
  place. Those changes need a table rebuild (create new table, copy, drop,
  rename). Prefer enforcing enums in Java rather than with a CHECK.
- Timestamps are `INTEGER` epoch milliseconds. Set them in Java; do not rely
  on DB defaults.
- Primary keys that must never be reused use `INTEGER PRIMARY KEY AUTOINCREMENT`.
- Do not put `PRAGMA foreign_keys` here: it is a no-op inside Flyway's
  transaction. The JDBC URL turns it on for every connection.
- `audit_logs` has insert-only triggers. A migration that must purge it has to
  drop and recreate them.
