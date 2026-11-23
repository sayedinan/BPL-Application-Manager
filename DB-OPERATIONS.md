# Database operations (SQLite)

The backend stores everything in one SQLite file. In Docker it lives on the named volume
`bplData` as `/data/bpl.db` (plus `bpl.db-wal` and `bpl.db-shm` while the app runs).
One backend instance only: SQLite allows a single writer process.

## Rules

- Keep `/data` on local disk or a Docker named volume. **Never on NFS/SMB**: SQLite file locking is
  unreliable there.
- Never copy `bpl.db` while the app is running (a WAL database copied file-by-file can be corrupt).
  Use the nightly backup below.
- Never run `docker compose down -v`: it deletes the volumes, including the database and the backups.
- `audit_logs` is insert-only at the database level (triggers). A future purge must drop them in its
  migration.

## Backups

The app writes `bpl-YYYYMMDD.db` into `/backups` (volume `bplBackups`) every night at 03:15, using
`VACUUM INTO`, which makes a consistent copy while the app is running. Files older than 14 days
are deleted. A failed backup run keeps the older files.

**Backups on the same disk do not survive disk or host loss.** Copy them off the host regularly, for
example with a scheduled job on the host:

    docker compose cp backend:/backups/bpl-$(date +%Y%m%d).db <off-host location>

(`docker compose cp` works on the distroless container; there is no shell or sqlite3 inside it.)

## Restore

1. `docker compose stop backend`
2. Put the chosen backup in place of the database. The container has no shell, so use a throwaway
   container on the same volumes (replace `<project>` with the compose project name; `docker volume ls`):

       docker run --rm -v <project>_bplData:/data -v <project>_bplBackups:/backups alpine \
         sh -c "rm -f /data/bpl.db-wal /data/bpl.db-shm && cp /backups/bpl-YYYYMMDD.db /data/bpl.db && chown 65532:65532 /data/bpl.db"

   The `-wal` and `-shm` files must be deleted, otherwise SQLite may replay a stale log over the restored file.
3. `docker compose start backend`
4. Everyone has to log in again only if the backup predates their session.

**These Docker steps have not been run yet** (no Docker daemon was available when they were written).
Test a restore at least once, on a scratch copy, before relying on the backups. The backup file
itself is verified by `SqliteBackupServiceTest` (`integrity_check` ok, data present).

## Monitoring

- Free disk space on the `bplData` volume.
- The size of `bpl.db-wal`. A very large WAL means a long-lived reader is holding back checkpoints;
  the app's transactions are short, so it should stay small.
- Log lines `SQLite backup written:` (nightly) and `SQLite backup failed` (alert on this).
- HTTP 503 with code `DATABASE_BUSY`: a request waited longer than `busy_timeout` (10 s) for the
  write lock. Rare occurrences are expected under load; frequent ones mean something holds a
  transaction open too long (see the rule in `AuditWriter`).

## Capacity

Log lines are capped at 500 per application and health history is pruned nightly (03:30), so the file
stays small. `audit_logs` is the only table that grows without bound.
