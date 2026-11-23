#!/usr/bin/env python3
"""One-off data copy: PostgreSQL (old BPL schema) -> SQLite (V1__sqlite_baseline).

Prereq: start the new backend once against an empty DB_PATH so Flyway creates the schema, then stop it.
Usage:  pg_to_sqlite.py --pg "postgresql://bpl:***@host/bpl_prod" --sqlite bpl.db [--dry-run] [--with-log-lines]
Everything is inserted in ONE SQLite transaction (all-or-nothing). --dry-run rolls back after verification.
Columns are matched by name from the SQLite schema, so PG-only columns (e.g. deleted_at) are dropped.
Not copied (transient): SPRING_SESSION*, idempotency_keys, websocket_*, flyway_schema_history.
"""
import argparse, datetime as dt, decimal, ipaddress, json, sqlite3, sys, uuid

EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
MS = dt.timedelta(milliseconds=1)

# parents before children
TABLES = ["users", "applications", "user_application_assignments", "audit_logs",
          "application_status_streak", "application_status_transitions",
          "application_health_config", "application_health_latest", "application_health_history",
          "application_maintenance", "sms_send_log"]
OPTIONAL = ["application_log_lines"]


def conv(v):
    if v is None or isinstance(v, (str, int, float)) and not isinstance(v, bool):
        return v
    if isinstance(v, bool):
        return int(v)
    if isinstance(v, dt.datetime):
        if v.tzinfo is None:
            v = v.replace(tzinfo=dt.timezone.utc)
        return (v - EPOCH) // MS            # integer math, no float rounding
    if isinstance(v, uuid.UUID):
        return str(v)
    if isinstance(v, (ipaddress.IPv4Interface, ipaddress.IPv6Interface)):
        return str(v.ip)
    if isinstance(v, (ipaddress.IPv4Address, ipaddress.IPv6Address)):
        return str(v)
    if isinstance(v, (dict, list)):
        return json.dumps(v, separators=(",", ":"))
    if isinstance(v, decimal.Decimal):
        return float(v)
    if isinstance(v, (bytes, memoryview)):
        return bytes(v)
    return str(v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pg", required=True)
    ap.add_argument("--sqlite", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--with-log-lines", action="store_true")
    a = ap.parse_args()
    import psycopg2
    pg = psycopg2.connect(a.pg)
    pg.set_session(readonly=True)
    lite = sqlite3.connect(a.sqlite, isolation_level=None)
    lite.execute("PRAGMA foreign_keys=ON")
    pc = pg.cursor()
    tables = TABLES + (OPTIONAL if a.with_log_lines else [])

    if lite.execute("select count(*) from users").fetchone()[0] != 0:
        sys.exit("target SQLite DB is not empty (users); start from a fresh Flyway-created file")
    pc.execute("select count(*) from users where deleted_at is not null")
    if pc.fetchone()[0] != 0:
        sys.exit("users.deleted_at has soft-deleted rows; V19 should have removed them - investigate")

    lite.execute("BEGIN IMMEDIATE")
    report = []
    try:
        for t in tables:
            cols = [r[1] for r in lite.execute(f"PRAGMA table_info({t})")]
            pc.execute(f'select {",".join(cols)} from {t}')
            rows = [tuple(conv(v) for v in r) for r in pc.fetchall()]
            lite.executemany(f'insert into {t} ({",".join(cols)}) values ({",".join("?"*len(cols))})', rows)
            pc.execute(f"select count(*) from {t}")
            expected = pc.fetchone()[0]
            got = lite.execute(f"select count(*) from {t}").fetchone()[0]
            report.append((t, expected, got))
        errs = []
        for t, e, g in report:
            print(f"{t:40} pg={e:8} sqlite={g:8} {'OK' if e == g else 'MISMATCH'}")
            if e != g:
                errs.append(t)
        fk = lite.execute("PRAGMA foreign_key_check").fetchall()
        if fk:
            errs.append(f"foreign_key_check: {fk[:5]}")
        for name, seq in lite.execute("select name, seq from sqlite_sequence"):
            pc.execute(f"select coalesce(max(id),0) from {name}")
            if seq < pc.fetchone()[0]:
                errs.append(f"sqlite_sequence too low for {name}")
        if errs:
            raise RuntimeError(f"verification failed: {errs}")
        if a.dry_run:
            lite.execute("ROLLBACK"); print("dry-run OK, rolled back")
        else:
            lite.execute("COMMIT")
            ic = lite.execute("PRAGMA integrity_check").fetchone()[0]
            print("committed; integrity_check =", ic)
            if ic != "ok":
                sys.exit(1)
    except Exception:
        if lite.in_transaction:
            lite.execute("ROLLBACK")
        raise


if __name__ == "__main__":
    main()
