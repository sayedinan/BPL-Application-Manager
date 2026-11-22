# SQLITE-MIGRATION-PLAN.md — PostgreSQL → SQLite for BPL Application Manager

> Status: proposed. Written from a static audit of `BPL-Application-Manager-db_change.zip`
> (nothing was built or run). Every "VERIFY" marker is something I could not prove from
> reading code; each one has a gate in the phase where it matters.
> Execution style (from `ways-of-working`): one file at a time, exact path + full content,
> `next` advances the queue, one-line imperative commits.

---

## 0. Goal, scope, decisions to confirm with TL first

**Goal:** the backend runs on a single SQLite file instead of PostgreSQL, with no loss of
behaviour (RBAC, audit, log poller, status poller, health, sessions, WebSocket limits).

**In scope:** driver/dialect, schema, entities, 2 native queries, transaction handling,
Docker/compose/CI, data migration of staging data, tests, backups, docs.
**Out of scope:** frontend (no change expected), Caddy, SSH/poller logic.

Confirm these before Phase 1 (they change the work):

| # | Question | My recommendation |
|---|---|---|
| D1 | Is a **single backend instance** acceptable? SQLite = one file, one process. | Yes (it is one instance today). Write it into SPEC. |
| D2 | **Replace** Postgres or support both? | Replace. Tag the last Postgres commit (`pre-sqlite`) so rollback is possible. Supporting both doubles every migration. |
| D3 | Keep staging data? | Yes — `audit_logs` is permanent by design (SPEC §3). Needs the Phase 8 data migration. |
| D4 | Timestamps stored as epoch-millis `INTEGER` or ISO text? | Epoch-millis (xerial driver default; cheap range queries and bucketing). Gate G3 proves it. |

---

## 1. What the audit found (facts, with where)

**Already portable**
- Hibernate/Spring Data JPA is already the data layer; almost all queries are JPQL.
- `IDENTITY` ids, `Instant` timestamps, `@PrePersist` setting `createdAt/updatedAt` (User).

**Postgres-only things that must change**
1. `db/migration/V1–V27`: `BIGSERIAL`, `TIMESTAMPTZ`, `INET`, `JSONB`, `BYTEA`, `~`/`~*` regex CHECKs
   (V15, V20), `char_length` CHECKs (V17), `gen_random_uuid()` + `UPDATE … FROM` (V19),
   `ALTER … ADD PRIMARY KEY/FOREIGN KEY/DROP CONSTRAINT/SET NOT NULL` (V10, V11, V13, V19, V27),
   `INTERVAL` default (V7), multi-column `ADD COLUMN` (V22). SQLite cannot run these.
2. `Application.serverIp` → `@JdbcTypeCode(SqlTypes.INET)` + `InetAddress`.
3. `AuditLog.detail` and `ApplicationHealthLatest.payload` → `@JdbcTypeCode(SqlTypes.JSON)`.
4. `ApplicationHealthHistoryRepository.findBuckets/findUptimeSlices` → native SQL with
   `extract(epoch …)`, `COUNT(*) FILTER`, `floor`.
5. `build.gradle.kts`: `flyway-database-postgresql`, `postgresql` driver, `testcontainers:postgresql`.
6. `docker-compose.yml`: `postgres` service, `pgdata` volume, `DB_URL/DB_USER/DB_PASSWORD`.

**Hazards specific to SQLite (found in this code)**
- **H1 — nested transaction deadlock.** `AuditWriter` is `@Transactional(REQUIRES_NEW)` and is called
  from `UserController.updateUser`, which is itself `@Transactional` and has already written
  (assignment DELETE / role UPDATE). On Postgres the second connection just works. On SQLite the
  outer transaction holds the write lock, the audit insert waits on a different connection, and
  fails with `SQLITE_BUSY`. 19 audit call sites exist (ApplicationController 5, AuthController 5,
  UserController 4, and 1 each in ApplicationGroupController, HealthController, MaintenanceController,
  StatusPollingOrchestrator, WebSocketAccessInterceptor). `updateUser` is confirmed; the others must be
  checked individually (Phase 5).
- **H2 — foreign keys are OFF by default in SQLite.** All `ON DELETE CASCADE` (application delete,
  user delete) silently stop working unless `PRAGMA foreign_keys=ON` is set on *every* connection.
- **H3 — IDs must never be reused.** `audit_logs.target_application_id` has no FK and relies on
  "IDs never reused". Plain `INTEGER PRIMARY KEY` in SQLite *can* reuse the highest id after a delete.
  Must use `AUTOINCREMENT`.
- **H4 — read→write upgrade in WAL mode** fails immediately with `SQLITE_BUSY_SNAPSHOT`
  (the busy timeout does not help). Fix with `transaction_mode=IMMEDIATE` (Phase 1).
- **H5 — Docker.** Backend runs `read_only: true`, user `65532`, distroless. SQLite needs a writable
  data directory (for `.db`, `-wal`, `-shm`), and the xerial driver extracts a native library into
  `java.io.tmpdir`. Docker `tmpfs` mounts are `noexec` by default, so loading it from `/tmp` fails.
- **H6 — the test suite does not touch a database.** `application-test.yml` points at H2, but
  `build.gradle.kts` has no H2 dependency. The only Spring tests are two `@WebMvcTest`s with mocks.
  `HardDeleteCascadeTest` is `assertTrue(true)` — it tests nothing. Today nothing proves cascades,
  unique indexes, or queries work on any database. (VERIFY by running `./gradlew test` first.)
- **H7 — pre-existing, not SQLite-caused:** `sms_send_log.application_id` has an FK **without** cascade,
  so deleting an application that has SMS log rows violates the FK on Postgres too. With SQLite and
  `foreign_keys=ON` it behaves the same. Decide in Phase 2 whether to add `ON DELETE SET NULL`.
- **H8 — schema drift:** `users.deleted_at` still exists in Postgres after V19 but the `User` entity
  has no such field. The baseline omits it.

---

## 2. Target design

```
backend container
  /app/app.jar
  /data/bpl.db       <- SQLite (named volume bplData), plus bpl.db-wal / bpl.db-shm
  /backups/          <- nightly VACUUM INTO copies (named volume bplBackups)
  /tmp               <- tmpfs with exec (native lib extraction)
```

**JDBC URL** (single place, env-driven):
```
jdbc:sqlite:${DB_PATH:./data/bpl.db}?foreign_keys=on&busy_timeout=10000&journal_mode=WAL&synchronous=NORMAL&transaction_mode=IMMEDIATE
```
- `foreign_keys=on` → fixes H2. `journal_mode=WAL` → readers don't block the writer.
  `busy_timeout=10000` → wait up to 10 s for the lock. `synchronous=NORMAL` → safe with WAL, faster.
  `transaction_mode=IMMEDIATE` → every transaction takes the write lock at BEGIN, fixes H4.
  (VERIFY: xerial accepts these as URL properties — Gate G1 proves each one.)
- Trade-off of `IMMEDIATE`: read-only transactions also serialize. At this app's scale (a handful of
  users, pollers writing every few seconds) that is fine. If measured contention appears, the escalation
  path is two datasources (one read pool DEFERRED, one write pool of size 1) — not needed on day one.
- Hikari: `maximum-pool-size: 5`, `connection-timeout: 15000` (must be larger than `busy_timeout`).

**Hibernate:** `org.hibernate.community.dialect.SQLiteDialect`, `ddl-auto: none` (SQLite schema
validation is unreliable; Flyway + tests are the guard instead).

**Types**

| Java | Column | Notes |
|---|---|---|
| `Instant` | `INTEGER` (epoch ms) | no DB defaults; entities always set the value |
| `UUID` | `TEXT` (36 chars) | `hibernate.type.preferred_uuid_jdbc_type=CHAR` (VERIFY G3) |
| `boolean` | `INTEGER` 0/1 | `CHECK (x IN (0,1))` |
| `serverIp` | `TEXT` | `String` in entity; validated by existing `IpAddresses` |
| JSON | `TEXT` | `CHECK (json_valid(...))`; entity maps JSON via a Jackson `AttributeConverter`, not `SqlTypes.JSON` |
| `Long` ids | `INTEGER PRIMARY KEY AUTOINCREMENT` | H3 |

---

## 3. Phases

Each phase ends with a **Gate**. Do not start the next phase until the gate passes.
Estimates assume one person working alongside QA duties.

### Phase 0 — Safety net (≈1 h)

1. Create branch and tag the last Postgres state.
   - `git switch -c feature/sqlite` and `git tag pre-sqlite` (tag first, on the commit you consider the last good Postgres one).
   - Commit: `Tag last PostgreSQL state before SQLite migration`.
2. ⚠ **Back up staging Postgres before anything else** (read-only, safe): on the staging host run
   `docker compose exec postgres pg_dump -U bpl -Fc bpl_prod > /home/<you>/bpl_prod_pre_sqlite.dump`.
   Copy the dump off the server. This is the rollback source.
3. Run the existing tests once and record the result: `cd backend && SSH_CIPHER_KEY='<32+ chars>' ./gradlew test`.
   (Check Java 25 toolchain is available first; do not install anything if it already runs.)
4. Note the per-table row counts on staging (needed in Phase 8):
   `docker compose exec postgres psql -U bpl bpl_prod -c "select relname, n_live_tup from pg_stat_user_tables order by 1;"`
   Prefer exact `count(*)` per table for the tables listed in Phase 8.

**Gate G0:** tag exists, dump exists off-server, test result recorded, row counts saved.

### Phase 1 — Dependencies and config (≈1 h)

Files: `backend/build.gradle.kts`, `backend/src/main/resources/application*.yml`.

1. `build.gradle.kts`
   - Remove: `flyway-database-postgresql`, `runtimeOnly("org.postgresql:postgresql")`,
     `testImplementation("org.testcontainers:postgresql")`, `testcontainers:junit-jupiter` (unused).
   - Add: `runtimeOnly("org.xerial:sqlite-jdbc")` and `implementation("org.hibernate.orm:hibernate-community-dialects")`.
     VERIFY versions: Boot's BOM may manage both; if not, pin to the Hibernate version Boot resolves
     (`./gradlew dependencies --configuration runtimeClasspath | grep hibernate-core`) and the newest
     stable `sqlite-jdbc`.
   - Keep `flyway-core`. VERIFY Flyway's SQLite support ships with your Flyway version; if startup says
     the database is unsupported, check that version's release notes for a separate SQLite module.
2. `application.yml` (shared): keep `jpa.open-in-view: false`; add under `spring.jpa.properties.hibernate`:
   `type.preferred_uuid_jdbc_type: CHAR`; keep `jdbc.time_zone: UTC`.
3. `application-dev.yml` and `application-prod.yml`: replace the datasource block with the URL above,
   no username/password, Hikari values above; set `spring.jpa.database-platform` to the SQLite dialect,
   `ddl-auto: none`; keep `flyway.enabled: true`, `locations: classpath:db/migration`.
   `DB_PATH` replaces `DB_URL/DB_USER/DB_PASSWORD`; the prod profile must fail fast if `DB_PATH` is unset
   (no default), dev defaults to `./data/bpl.db`.
4. `.gitignore`: add `data/`, `*.db`, `*.db-wal`, `*.db-shm`, `backups/`.
5. Delete `temp/application-dev.yml` if it is just an old copy (it is git-ignored; ignore otherwise).

Commits: `Swap PostgreSQL driver for SQLite and community dialect`, `Point dev and prod profiles at SQLite file`.

**Gate G1 (connection pragmas):** write a throwaway test (kept later as `SqlitePragmaTest`) that opens a
connection from the app's DataSource and asserts: `PRAGMA foreign_keys` = 1, `PRAGMA journal_mode` = `wal`,
`PRAGMA busy_timeout` = 10000. Run it against **two different pooled connections** — foreign_keys is
per-connection and this is where it silently fails.

### Phase 2 — New baseline schema (≈3 h)

Files: move `db/migration/V1–V27` to `db/migration-postgres-archive/` (kept in git history and the tag; not on
the classpath so Flyway ignores them), then add one new `db/migration/V1__sqlite_baseline.sql`, and rewrite
`db/migration/README.md`.

Why a baseline and not a port: V10/V11/V13/V19/V27 use `ALTER` forms SQLite doesn't support, and the final
state is all that matters for a new database. Future changes use additive SQLite migrations starting at V2.

**DDL below is derived from the migrations V1–V27, not from the entities. Diff it against every `@Entity`
column-by-column before accepting it (Phase 3 step 1).**

```sql
PRAGMA foreign_keys = ON;   -- Flyway runs on one connection; the URL also sets it

CREATE TABLE users (
    id                   TEXT    PRIMARY KEY,                       -- UUID, 36 chars
    username             TEXT    NOT NULL,
    full_name            TEXT,
    password_hash        TEXT    NOT NULL,
    role                 TEXT    NOT NULL CHECK (role IN ('SYS_ADMIN','ADMIN','USER')),
    email                TEXT,
    phone_number         TEXT    CHECK (phone_number IS NULL OR
                                  (length(phone_number) = 13 AND
                                   phone_number GLOB '8801[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]')),
    must_change_password INTEGER NOT NULL DEFAULT 1 CHECK (must_change_password IN (0,1)),
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_users_username ON users(username);

CREATE TABLE applications (
    id                              INTEGER PRIMARY KEY AUTOINCREMENT,   -- AUTOINCREMENT: ids never reused (H3)
    name                            TEXT    NOT NULL,
    server_ip                       TEXT    NOT NULL,
    ssh_username                    TEXT    NOT NULL,
    ssh_password_enc                TEXT    NOT NULL,
    ssh_host_key_fingerprint        TEXT    NOT NULL,
    start_script                    TEXT    NOT NULL,
    stop_script                     TEXT    NOT NULL,
    log_script                      TEXT    NOT NULL,
    group_name                      TEXT,
    email_start_message             TEXT,
    email_stop_message              TEXT,
    email_external_online_message   TEXT,
    email_external_offline_message  TEXT,
    sms_start_message               TEXT CHECK (sms_start_message IS NULL OR length(sms_start_message) <= 160),
    sms_stop_message                TEXT CHECK (sms_stop_message IS NULL OR length(sms_stop_message) <= 160),
    sms_external_online_message     TEXT CHECK (sms_external_online_message IS NULL OR length(sms_external_online_message) <= 160),
    sms_external_offline_message    TEXT CHECK (sms_external_offline_message IS NULL OR length(sms_external_offline_message) <= 160),
    deleted_at                      INTEGER,                              -- kept only if Application entity still maps it
    created_at                      INTEGER NOT NULL,
    updated_at                      INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_applications_name_lower ON applications(lower(name));

CREATE TABLE user_application_assignments (
    user_id        TEXT    NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    assigned_at    INTEGER NOT NULL,
    PRIMARY KEY (user_id, application_id)
);

CREATE TABLE audit_logs (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp               INTEGER NOT NULL,
    actor_username          TEXT    NOT NULL,
    actor_role              TEXT    NOT NULL,
    action_type             TEXT    NOT NULL,   -- enum CHECK intentionally dropped: V10/V13 each changed it,
                                                -- and every change would need a full table rebuild in SQLite.
                                                -- The Java enum/validator is the guard.
    target_application_id   INTEGER,            -- no FK, never reused
    target_application_name TEXT,
    target_user_id          TEXT,
    detail                  TEXT CHECK (detail IS NULL OR json_valid(detail)),
    result                  TEXT    NOT NULL CHECK (result IN ('SUCCESS','FAILURE')),
    source_ip               TEXT
);
CREATE INDEX idx_audit_timestamp   ON audit_logs(timestamp DESC);
CREATE INDEX idx_audit_actor       ON audit_logs(actor_username);
CREATE INDEX idx_audit_application ON audit_logs(target_application_id);
-- Optional hardening (recommended): make "insert-only" a DB guarantee.
CREATE TRIGGER audit_logs_no_update BEFORE UPDATE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'audit_logs is insert-only'); END;
CREATE TRIGGER audit_logs_no_delete BEFORE DELETE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'audit_logs is insert-only'); END;

CREATE TABLE application_log_lines (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    line_number    INTEGER NOT NULL,
    content        TEXT    NOT NULL,
    captured_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_application_log_unique ON application_log_lines(application_id, line_number);
CREATE INDEX        idx_application_log_latest ON application_log_lines(application_id, captured_at DESC);

CREATE TABLE idempotency_keys (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT    NOT NULL,
    user_id         TEXT    NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    application_id  INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL            -- was DEFAULT now()+24h; entity/service must set it (check IdempotencyService)
);
CREATE UNIQUE INDEX idx_idempotency_unique ON idempotency_keys(user_id, application_id, idempotency_key);
CREATE INDEX        idx_idempotency_expiry ON idempotency_keys(expires_at);

CREATE TABLE websocket_connections (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   TEXT    NOT NULL,
    username     TEXT    NOT NULL,
    connected_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ws_connections_session  ON websocket_connections(session_id);
CREATE INDEX        idx_ws_connections_username ON websocket_connections(username);

CREATE TABLE websocket_subscriptions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT    NOT NULL,
    subscription_id TEXT    NOT NULL,
    topic_key       TEXT    NOT NULL,
    subscribed_at   INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ws_subscriptions_session_sub ON websocket_subscriptions(session_id, subscription_id);
CREATE INDEX        idx_ws_subscriptions_topic       ON websocket_subscriptions(topic_key);

CREATE TABLE application_status_transitions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id  INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    online          INTEGER NOT NULL CHECK (online IN (0,1)),
    transitioned_at INTEGER NOT NULL
);
CREATE INDEX idx_status_transitions_app ON application_status_transitions(application_id, transitioned_at DESC);

CREATE TABLE application_status_streak (
    application_id    INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    online            INTEGER NOT NULL CHECK (online IN (0,1)),
    streak_started_at INTEGER NOT NULL
);

CREATE TABLE sms_send_log (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id         INTEGER REFERENCES applications(id),   -- H7: decide SET NULL vs keep as-is
    recipient_phone_number TEXT    NOT NULL,
    event_type             TEXT    NOT NULL,
    is_error               INTEGER NOT NULL CHECK (is_error IN (0,1)),
    inserted_sms_id        TEXT,
    response_message       TEXT,
    sent_at                INTEGER NOT NULL
);
CREATE INDEX idx_sms_send_log_application_id ON sms_send_log(application_id);
CREATE INDEX idx_sms_send_log_sent_at        ON sms_send_log(sent_at);

CREATE TABLE application_health_config (
    application_id        INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    url                   TEXT    NOT NULL CHECK (url LIKE 'http://%' OR url LIKE 'https://%'),
    api_key_enc           TEXT,
    tls_pin_sha256        TEXT    CHECK (tls_pin_sha256 IS NULL OR
                                    (length(tls_pin_sha256) = 64 AND tls_pin_sha256 NOT GLOB '*[^0-9a-f]*')),
    poll_interval_seconds INTEGER NOT NULL DEFAULT 10 CHECK (poll_interval_seconds BETWEEN 3 AND 300),
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL,
    CONSTRAINT chk_health_config_pin_needs_https CHECK (tls_pin_sha256 IS NULL OR url LIKE 'https://%')
);

CREATE TABLE application_health_latest (
    application_id       INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    checked_at           INTEGER NOT NULL,
    reachable            INTEGER NOT NULL CHECK (reachable IN (0,1)),
    http_status          INTEGER,
    response_ms          INTEGER CHECK (response_ms >= 0),
    status               TEXT    CHECK (status IN ('UP','DEGRADED','DOWN','UNKNOWN')),
    payload              TEXT    CHECK (payload IS NULL OR json_valid(payload)),
    error                TEXT,
    ssl_not_after        INTEGER,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0)
);

CREATE TABLE application_health_history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    checked_at     INTEGER NOT NULL,
    reachable      INTEGER NOT NULL CHECK (reachable IN (0,1)),
    response_ms    INTEGER CHECK (response_ms >= 0),
    status         TEXT    CHECK (status IN ('UP','DEGRADED','DOWN','UNKNOWN')),
    cpu_percent    REAL    CHECK (cpu_percent    BETWEEN 0 AND 100),
    memory_percent REAL    CHECK (memory_percent BETWEEN 0 AND 100),
    disk_percent   REAL    CHECK (disk_percent   BETWEEN 0 AND 100)
);
CREATE INDEX idx_health_history_app ON application_health_history(application_id, checked_at DESC);

CREATE TABLE application_maintenance (
    application_id INTEGER PRIMARY KEY REFERENCES applications(id) ON DELETE CASCADE,
    starts_at      INTEGER NOT NULL,
    ends_at        INTEGER NOT NULL,
    note           TEXT    CHECK (note IS NULL OR length(note) <= 200),
    started_by     TEXT    NOT NULL,
    started_at     INTEGER NOT NULL,
    CONSTRAINT application_maintenance_window_order CHECK (ends_at > starts_at)
);

-- Spring Session JDBC (V6). Prefer the jar's own schema-sqlite.sql if it exists:
--   unzip -p ~/.gradle/caches/**/spring-session-jdbc-*.jar org/springframework/session/jdbc/schema-sqlite.sql
-- Otherwise this adaptation (BYTEA -> BLOB):
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            TEXT    NOT NULL PRIMARY KEY,
    SESSION_ID            TEXT    NOT NULL,
    CREATION_TIME         INTEGER NOT NULL,
    LAST_ACCESS_TIME      INTEGER NOT NULL,
    MAX_INACTIVE_INTERVAL INTEGER NOT NULL,
    EXPIRY_TIME           INTEGER NOT NULL,
    PRINCIPAL_NAME        TEXT
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX        SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX        SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID TEXT NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME     TEXT NOT NULL,
    ATTRIBUTE_BYTES    BLOB NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);
```

Notes:
- Case-insensitive name uniqueness stays at DB level via `lower(name)` (ASCII-only, and names are
  restricted to `[a-zA-Z0-9_-]`).
- Removed DB checks (action_type enum) are re-asserted in Java (Phase 3).
- Commit: `Add SQLite baseline schema replacing PostgreSQL migrations`.

**Gate G2:** with the app *not* started, apply the baseline to an empty file using Flyway through the app
(`./gradlew bootRun` with `DB_PATH=./data/test.db` is acceptable once Phase 3 compiles; before that, run the
SQL directly with the `sqlite3` CLI if it is already installed — check `sqlite3 --version` first, do not
install). Then check `PRAGMA foreign_key_check;` returns nothing and `PRAGMA integrity_check;` returns `ok`.

### Phase 3 — Entities and mappings (≈3 h)

Do one file at a time.

1. **Entity ↔ DDL diff.** For each `@Entity` list `@Column` names/nullability against the DDL in Phase 2
   (esp. `Application`: confirm whether `deletedAt` is mapped; `User`, `IdempotencyKey`,
   `WebSocketConnection/Subscription`, `ApplicationMaintenance`, `ApplicationHealthConfig`). Fix whichever is wrong.
2. **No DB-default dependence.** Every column I removed a `DEFAULT now()` from must be set by Java:
   check `IdempotencyKey.expiresAt` (was `now()+24h`), `WebSocketConnection.connectedAt`,
   `WebSocketSubscription.subscribedAt` (sets it), `ApplicationStatusStreak.streakStartedAt`,
   `ApplicationStatusTransition.transitionedAt`, `ApplicationLogLine.capturedAt` (sets it),
   `ApplicationHealthHistory.checkedAt`, `ApplicationMaintenance.startsAt`. Any `null` here now fails
   with `NOT NULL constraint failed` instead of silently defaulting — which is what we want.
3. `Application.serverIp`: change type to `String`, drop `@JdbcTypeCode(SqlTypes.INET)` and
   `InetAddress`. Keep validation in `IpAddresses`/controller DTO. Update `ApplicationController`
   and any code calling `getServerIp()` expecting `InetAddress` (use `InetAddress.getByName` at the
   SSH call site). Commit: `Store application server IP as validated text`.
4. `ApplicationHealthLatest.payload`: remove `@JdbcTypeCode(SqlTypes.JSON)`; plain `String` on a `TEXT`
   column (it is already a `String`, and `HealthReadService.parseJson` already tolerates `toString()`).
5. `AuditLog.detail` (`Map<String,Object>`): replace `@JdbcTypeCode(SqlTypes.JSON)` with
   `@Convert(converter = JsonMapConverter.class)` — new class `common/JsonMapConverter.java`
   (`AttributeConverter<Map<String,Object>, String>`, Jackson `ObjectMapper`, null-safe). Avoids relying on
   the community dialect's JSON support.
6. **UUID columns** (`User.id`, `UserApplicationAssignmentId.userId`, `AuditLog.targetUserId`,
   `IdempotencyKey.userId`): covered by `hibernate.type.preferred_uuid_jdbc_type=CHAR`. If Gate G3 shows
   it does not apply to `@EmbeddedId` or `GenerationType.UUID`, add `@JdbcTypeCode(SqlTypes.CHAR)`
   on those fields explicitly.
7. **Action type guard in Java.** `action_type` no longer has a DB CHECK. Confirm `AuditWriter` callers pass
   constants from one enum/constant class; if they pass raw strings, add a `Set<String> ALLOWED` check in
   `AuditWriter.insert` that throws `IllegalArgumentException`. The list is in V13 (17 values incl.
   `UPDATE_USER`, `APPLICATION_ONLINE`, `APPLICATION_OFFLINE`).
8. Native queries in `UserRepository` (3): plain joins, portable; leave, but G3 must exercise them.

**Gate G3 (type round-trip smoke test, kept as `SqliteTypeRoundTripTest`, `@SpringBootTest`/`@DataJpaTest`
on a temp file DB):**
- save+read a `User` (UUID, Instant, boolean) → values equal; raw `select typeof(id), typeof(created_at),
  typeof(must_change_password) from users` returns `text, integer, integer`.
- save+read an `Application` (String IP) and an `AuditLog` with a nested `detail` map → equal after reload;
  `json_valid(detail)` is 1.
- save `ApplicationHealthLatest` with payload JSON; read back identical.
- `UserApplicationAssignment` with `@EmbeddedId` round-trips; `findAssignedApplicationIds` and
  `findAssignedUserEmails` native queries return expected rows.
- If `typeof(created_at)` is `text` instead of `integer`, stop: either set xerial `date_class=INTEGER`
  in the URL or switch the whole plan to ISO text (Phase 6's bucketing SQL changes).

### Phase 4 — Health queries (≈2 h)

File: `health/ApplicationHealthHistoryRepository.java` (+ its callers `HealthController`, `HealthCheckService`).

Rewrite the two native queries for epoch-millis `checked_at`:

```sql
-- findBuckets  (bucketSeconds -> bucketMs computed in Java and passed as :bucketMs)
SELECT (checked_at / :bucketMs) * (:bucketMs / 1000)                      AS bucket,       -- epoch seconds
       COUNT(*)                                                            AS checks,
       COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed,
       AVG(response_ms), AVG(cpu_percent), AVG(memory_percent), AVG(disk_percent)
FROM application_health_history
WHERE application_id = :applicationId AND checked_at >= :since
GROUP BY bucket ORDER BY bucket;

-- findUptimeSlices (startMs, sliceMs computed in Java)
SELECT CAST((checked_at - :startMs) / :sliceMs AS INTEGER) AS slice,
       COUNT(*) AS checks,
       COUNT(*) FILTER (WHERE NOT reachable OR status IS NULL OR status = 'DOWN') AS failed
FROM application_health_history
WHERE application_id = :applicationId AND checked_at >= :rangeStart AND checked_at < :rangeEnd
GROUP BY slice;
```
- `FILTER` needs SQLite ≥ 3.30 (any current xerial release bundles newer — VERIFY the bundled version in G4).
- Integer division in SQLite truncates, equal to `floor` for non-negative values (all epoch times here).
- Bind types matter: pass `long`s, not `int`/`double`, so division stays integral.
- Result mapping: SQLite returns `Long`/`Double`; make sure callers read `((Number) row[i]).longValue()/doubleValue()`
  and never cast to `BigDecimal` or `Integer`.
- Remove the "PostgreSQL-specific on purpose" comment.

Commit: `Rewrite health bucket queries for SQLite epoch-millis`.

**Gate G4:** `HealthHistoryQueryTest` inserts a known set of rows (e.g. 10 checks across 3 buckets, 2 failures) and
asserts exact bucket counts, failed counts and averages; asserts `select sqlite_version()` ≥ 3.30.

### Phase 5 — Transactions and concurrency (≈4 h)

This is the highest-risk phase (H1, H4).

1. **Inventory the 19 `auditWriter` call sites** (list in §1). For each, write down: is the calling method
   inside an active transaction that has already written? Command: `grep -rn "auditWriter\." backend/src/main/java`.
2. **`UserController.updateUser`** (confirmed nested): remove method-level `@Transactional`; move the DB
   changes (role update + assignment delete/insert) into a small `@Transactional` method on a service bean
   (or use `TransactionTemplate`) that commits **before** `auditWriter.write(...)` runs. Success audit then
   happens after commit; failure audit (rollback case) happens in the `catch` outside the transaction.
   Behaviour preserved: failure rows are still recorded, because the audit write is no longer coupled to the
   rolled-back transaction.
3. Fix any other site found in step 1 the same way. Rule going forward: **never call `AuditWriter` from
   inside an open write transaction.** Add a comment on `AuditWriter` and update the
   `audit-log-coverage` skill/reviewer docs in `.claude/` with the rule.
4. `LogPollingOrchestrator` / `StatusPollingOrchestrator` save per line / per transition. At one commit per
   line this is slow on SQLite with `synchronous=NORMAL` under WAL only if lines arrive in large batches.
   VERIFY with a batch of 500 lines in G5; if > 1 s, wrap each poll tick's saves in one transaction
   (`TransactionTemplate`) — one commit per tick.
5. **Concurrency error mapping.** In `GlobalExceptionHandler`, map lock timeouts
   (`CannotAcquireLockException`, or `SQLException` with SQLite BUSY code) to HTTP 503 with a stable error
   code (add to `ErrorCode` and SPEC §4.2) so the UI shows "busy, retry" rather than a 500.
6. Spring Session writes happen at request end (outside the controller transaction) — no change, but G5 covers it.
7. `@Async` notification threads write `sms_send_log`: short transactions, no change.

Commits: `Run user update DB work in its own transaction before auditing`, `Map SQLite lock timeout to 503`.

**Gate G5 (`SqliteConcurrencyTest`):** 8 threads for 20 s: 3 writing log lines + status transitions, 2 calling
`AuditWriter.write`, 1 updating users, 1 reading dashboard queries, 1 session create/delete. Pass criteria: no
`SQLITE_BUSY` escapes, no deadlock, final row counts equal the number of successful operations, total run
time within a sane bound (record it as the baseline).

### Phase 6 — Flyway, startup, seed (≈1 h)

1. Start the app against an **empty** file: `DB_PATH=./data/dev.db SSH_CIPHER_KEY=... ./gradlew bootRun`.
   Expect: Flyway applies `V1__sqlite_baseline`, `SysAdminSeeder` creates the first Sys.Admin with the
   one-time password, login works from the frontend (`http://localhost:5173`).
2. Confirm the `flyway_schema_history` table exists in the file and `validate-on-migrate` passes on a
   second boot.
3. `application-test.yml`: replace H2 config with a per-test temp-file SQLite DB + Flyway **enabled**
   (so tests run the real baseline). Do not use `:memory:` with a pool — each connection would get its own
   empty database; use a temp file or `file:memdb1?mode=memory&cache=shared`.
4. Remove the `read_only`-unfriendly assumption: `server.port` etc. unchanged.

**Gate G6:** clean boot twice in a row (second boot = no migration), login + change-password + create user +
create application through the UI all succeed; restart the backend and the session is still valid
(proves SPRING_SESSION on SQLite works).

### Phase 7 — Docker, compose, CI (≈2 h)

Files: `backend/Dockerfile`, `docker-compose.yml`, `.gitlab-ci.yml`, `run.sh`, `run.bat`, `run-stop.bat`.

1. `Dockerfile`: in the build stage create the data dir, and copy it into the runtime stage with the right owner
   so a fresh named volume inherits it (distroless has no shell to `chown`):
   `RUN mkdir -p /out/data /out/backups` … `COPY --from=build --chown=65532:65532 /out/data /data`
   (same for `/backups`).
2. `docker-compose.yml`:
   - Delete the `postgres` service and the `pgdata` volume entry; drop `depends_on: postgres`.
   - Backend env: remove `DB_URL`, `DB_USER`, `DB_PASSWORD`; add `DB_PATH: /data/bpl.db`.
   - Backend volumes: `bplData:/data` and `bplBackups:/backups`; add both under top-level `volumes:`.
   - Keep `read_only: true`, but change tmpfs to allow native-lib loading (H5):
     `tmpfs: - /tmp:rw,exec,mode=1777`. VERIFY it boots; if it still cannot load the native library, set
     `JAVA_TOOL_OPTIONS=-Dorg.sqlite.tmpdir=/data/tmp` and create that directory in the image.
   - Remove port `127.0.0.1:15432`.
   - ⚠ Do **not** run `docker compose down -v` at any point until Phase 9 sign-off: `-v` deletes volumes
     (including the old `pgdata`, your only copy besides the dump).
3. `.gitlab-ci.yml`: add `SSH_CIPHER_KEY` as a CI variable for `test-backend` if it is not already (tests refuse
   to run without it). No database service needed any more.
4. `run.sh` / `run.bat` / `run-stop.bat`: remove the "start PostgreSQL" steps and text.
5. Staging `.env`: `DB_PASSWORD` is no longer used by the backend (leave until sign-off, then remove).

Commits: `Run backend on SQLite volume and drop PostgreSQL service`, `Remove PostgreSQL steps from run scripts`.

**Gate G7:** `docker compose build backend && docker compose up -d` on a scratch copy (not staging yet):
backend healthy, login works, `docker compose exec` into nothing (distroless has no shell) — verify by
`docker compose cp backend:/data/bpl.db ./check.db` then `PRAGMA integrity_check`. Restart the container;
data persists.

### Phase 8 — Data migration from staging Postgres (≈4 h incl. dry run)

One-off script, kept in repo as `tools/pg_to_sqlite.py` (run in a throwaway container, so nothing is installed on
the host: `docker run --rm -v "$PWD":/w -w /w --network <compose network> python:3.12-slim sh -c "pip install psycopg2-binary && python tools/pg_to_sqlite.py"`).

**Copy** (parents before children, preserving primary keys):

| Order | Table | Conversions |
|---|---|---|
| 1 | `users` | `id` UUID→text; timestamps→epoch ms; bool→0/1; drop `deleted_at` |
| 2 | `applications` | `server_ip` inet→`host(server_ip)` text; timestamps→ms |
| 3 | `user_application_assignments` | UUID→text; ts→ms |
| 4 | `audit_logs` | ts→ms; `target_user_id` UUID→text; `detail` jsonb→json text. **Must copy** (permanent record) |
| 5 | `application_status_streak`, `application_status_transitions` | ts→ms; bool→0/1 |
| 6 | `application_health_config`, `application_health_latest`, `application_health_history` | ts→ms; `payload` jsonb→text; bool→0/1 |
| 7 | `application_maintenance`, `sms_send_log` | ts→ms; bool→0/1 |
| 8 | `application_log_lines` | optional (rebuilt by the poller); copy only if you want the last 500 lines to survive |

**Do not copy:** `SPRING_SESSION*` (everyone re-logs in once), `idempotency_keys`, `websocket_*`
(transient), `flyway_schema_history` (new history starts at V1).

Steps:
1. Start the new backend once against a new empty file so Flyway creates the schema, then stop it.
2. Run the script with `INSERT` into the existing tables inside **one transaction** (all-or-nothing).
3. `AUTOINCREMENT` counters: inserting explicit ids updates `sqlite_sequence` automatically; verify with
   `select name, seq from sqlite_sequence` that each `seq` ≥ the Postgres `max(id)`.
4. Timestamps: convert with `int(dt.timestamp()*1000)`; psycopg2 returns timezone-aware datetimes for
   `timestamptz`, so this is exact.

**Gate G8 (verification, all must pass):**
- Row count per copied table equals the Postgres count from Phase 0 (script prints a table).
- `PRAGMA foreign_key_check;` empty, `PRAGMA integrity_check;` = `ok`.
- Spot check 5 audit rows, 3 applications (decrypt one `ssh_password_enc` via the app's test-connection
  to prove the cipher key and ciphertext survived), the Sys.Admin can log in with the same password.
- Rehearse the whole phase on a copy of the dump first (restore the dump into a scratch Postgres container),
  and only then do the real cutover.

### Phase 9 — Tests worth keeping (≈3 h, can overlap Phases 3–5)

Replace `HardDeleteCascadeTest` (currently `assertTrue(true)`) and add:

| Test | Proves |
|---|---|
| `SqlitePragmaTest` | foreign_keys/WAL/busy_timeout on pooled connections (G1) |
| `SqliteTypeRoundTripTest` | types and UUID/JSON/Instant mapping (G3) |
| `HardDeleteCascadeTest` (real) | deleting an application removes assignments, log lines, health rows, maintenance, streak; deleting a user removes assignments + idempotency keys; audit rows remain |
| `AuditInsertOnlyTest` | UPDATE/DELETE on `audit_logs` aborts (triggers) |
| `ApplicationNameUniquenessTest` | `Foo` vs `foo` rejected; id not reused after delete (H3) |
| `HealthHistoryQueryTest` | G4 |
| `SqliteConcurrencyTest` | G5 |
| `FlywayFromScratchTest` | baseline applies to an empty file; second run is a no-op |
| existing `@WebMvcTest`s | keep unchanged |

CI runs all of these on every push (they need no external database).

### Phase 10 — Backups and operations (≈2 h)

1. Nightly backup inside the app (no shell/sqlite3 in distroless): a `@Scheduled(cron = "0 15 3 * * *")` job that runs
   `VACUUM INTO '/backups/bpl-YYYYMMDD.db'` through `JdbcTemplate`, then deletes files older than 14 days.
   `VACUUM INTO` produces a consistent copy while the app is running (a plain file copy of a WAL database can be
   corrupt). Add a test that the produced file passes `PRAGMA integrity_check`.
2. Restore procedure (document in README): stop backend → copy the chosen backup over `/data/bpl.db`, delete
   `bpl.db-wal` and `bpl.db-shm` → start backend.
3. Never put `/data` on NFS/SMB (SQLite file locking is unreliable there). Local disk / Docker named volume only.
4. Monitor: free disk space on the volume; `-wal` file growth (a very large WAL means a long-lived reader; the
   app's short transactions should keep it small). Optional: `PRAGMA wal_checkpoint(TRUNCATE)` in the same nightly job.
5. Capacity note: log lines are capped at 500/app, health history is pruned nightly (`HealthCheckService` 03:30),
   so the file stays small; `audit_logs` is the only unbounded table.

### Phase 11 — Documentation (≈1 h)

Update: `SPEC.md` (line 16 "single JAR + PostgreSQL", line 340 "Spring Session JDBC → PostgreSQL", §3 DDL,
§8.3, §13.1 env vars, add D1 single-instance constraint), `STATUS-REDESIGN.md` references, `db/migration/README.md`,
`.claude/skills/*` and `.claude/agents/*` mentions of PostgreSQL/Flyway rules, and `PLANNING-NOTES.md` (append-only:
record the decision and why). Commit: `Document SQLite as the database in SPEC and runbooks`.

### Phase 12 — Cutover and rollback (≈1 h on the day)

**Cutover runbook (staging):**
1. Announce a short maintenance window. Take a fresh `pg_dump` (Phase 0 command).
2. ⚠ Stop the backend only (`docker compose stop backend frontend`); leave Postgres running for the export.
3. Run the Phase 8 script against staging → new `bpl.db`; run its verification (G8).
4. `git pull` the `feature/sqlite` merge on `master` (staging tracks `origin/master`), `docker compose build`,
   `docker compose up -d`.
5. Smoke test: login, dashboard, start/stop one **non-production** app (the `block-staging-ip.sh` hook still
   applies — never point at the shared BPL engine IP without explicit confirmation), open logs, WebSocket live
   status, create/delete a test user, view audit log.
6. Leave the old `pgdata` volume and the dump untouched for **2 weeks**, then remove (⚠ `docker volume rm` is
   irreversible — only after TL sign-off).

**Rollback (any time within the 2 weeks):** `docker compose stop`, `git checkout pre-sqlite` (or revert the
merge), `docker compose up -d` — Postgres + `pgdata` come back as they were. Any audit rows written on SQLite
after cutover would be lost on rollback unless exported first; note the cutover time so they can be replayed.

---

## 4. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Nested audit transaction deadlock (H1) | certain if untouched | writes fail | Phase 5 step 2–3, G5 |
| Foreign keys off on some connection (H2) | medium | orphaned rows, cascades dead | URL param + G1 + real cascade test |
| Timestamp stored as text/mixed formats | medium | wrong range queries | no DB defaults, G3 `typeof()` check |
| `FILTER`/driver version mismatch | low | health charts break | G4 asserts version |
| Native lib can't load (noexec tmpfs, musl/glibc) | medium | backend won't start | Phase 7 step 2, G7 |
| Data migration loses/garbles rows | low (rehearsed) | audit history damaged | dry run, counts, single transaction, dump kept |
| Write contention under load | low at this scale | 503s | busy_timeout, IMMEDIATE, 503 mapping, escalation to split pools |
| Unknown Flyway/Hibernate-dialect incompatibility | low-medium | schedule slip | Phase 1/G1 and G3 fail early, before real work |
| Future scale-out needed | future | rewrite | recorded as D1 in SPEC; Postgres code stays in the `pre-sqlite` tag |

## 5. Definition of done

- All gates G0–G8 passed and recorded in this file.
- `./gradlew test` green in CI with no external database.
- Staging running on SQLite for 2 weeks with nightly backups verified (one restore test performed).
- SPEC and runbooks updated; Postgres artifacts removed from the active tree.

## 6. Time estimate

Phases 0–12 ≈ 25–30 working hours (~4–5 days alongside normal QA work). Biggest uncertainties: Phase 5
(call-site audit) and Phase 8 (data rehearsal). Suggested order matches the phase numbers; Phases 3, 4, 5 and 9
can be interleaved because each gate is a test.

## 7. Open questions for TL

1. Confirm D1 (single instance) and D2 (replace, not dual-support).
2. Is the staging data worth preserving beyond `audit_logs`, or can health history/log lines start fresh?
3. Should deleting an application with SMS log rows be allowed (H7: `ON DELETE SET NULL`)?
4. Is dropping the DB-level `action_type` CHECK acceptable (Java enum becomes the only guard)?
