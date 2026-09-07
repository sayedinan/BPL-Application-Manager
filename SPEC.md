# BPL Order Application Admin — Specification

**Version:** 1.0
**Status:** Locked for Implementation
**Last Updated:** 2026-09-06

---

## 1. Overview

A web-based administration tool for managing BPL (and future PCL) order applications. Provides role-based access control, SSH-driven application lifecycle management (start/stop), real-time log streaming, and comprehensive audit logging.

**Core Principles:**
- **No mock mode** — every application is real, SSH-backed
- **Security first** — encrypted credentials, session auth, strict CSP
- **Operational simplicity** — single JAR + PostgreSQL, minimal moving parts
- **Auditability** — insert-only audit log, permanent history

---

## 2. Role Model (3-Tier)

| Role | Can Create | Can Manage | Application Access | Audit Log |
|---|---|---|---|---|
| **Sys.Admin** | Applications, Admins, Users | All | All applications | Full |
| **Admin** | Users | Users | All applications | Full |
| **User** | — | — | Assigned applications only | None |

### Rules
- **No self-deletion** — no role can delete their own account
- **Admin ceiling** — Admin cannot create/promote to Admin or Sys.Admin
- **Application assignment** — Users gain access via "Assigned Applications" (not "roles")
- **Terminology:** "Role" = RBAC tier (SYS_ADMIN/ADMIN/USER). "Application Assignment" = per-application access grant.

---

## 3. Data Model

### 3.1 Tables

#### `users`
```sql
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,        -- Argon2id
    role            VARCHAR(16)  NOT NULL CHECK (role IN ('SYS_ADMIN','ADMIN','USER')),
    must_change_password BOOLEAN NOT NULL DEFAULT true,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NULL;
```

#### `applications`
```sql
CREATE TABLE applications (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(64)  NOT NULL UNIQUE,
    server_ip             INET         NOT NULL,
    ssh_username          VARCHAR(64)  NOT NULL,
    ssh_password_enc      TEXT         NOT NULL,          -- Jasypt AES-256-GCM
    ssh_host_key_fingerprint VARCHAR(64) NOT NULL,        -- SHA256
    start_script          TEXT         NOT NULL,
    stop_script           TEXT         NOT NULL,
    log_script            TEXT         NOT NULL,
    poll_interval_seconds INT          NOT NULL DEFAULT 5,
    status                VARCHAR(16)  NOT NULL DEFAULT 'STOPPED'
                            CHECK (status IN ('RUNNING','STOPPED','STARTING','STOPPING','ERROR')),
    started_at            TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_applications_deleted ON applications(deleted_at) WHERE deleted_at IS NULL;
```

#### `user_application_assignments`
```sql
CREATE TABLE user_application_assignments (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    application_id  BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, application_id)
);
```

#### `audit_logs` (Insert-only, no FK to applications)
```sql
CREATE TABLE audit_logs (
    id                 BIGSERIAL PRIMARY KEY,
    timestamp          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_username     VARCHAR(64) NOT NULL,
    actor_role         VARCHAR(16) NOT NULL,
    action_type        VARCHAR(32) NOT NULL,  -- See §3.2
    target_application_id   BIGINT,                -- No FK, never reused
    target_application_name VARCHAR(64),           -- Snapshot for readability
    target_user_id     BIGINT,
    detail             JSONB,
    result             VARCHAR(16) NOT NULL CHECK (result IN ('SUCCESS','FAILURE'))
);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp DESC);
CREATE INDEX idx_audit_actor ON audit_logs(actor_username);
CREATE INDEX idx_audit_application ON audit_logs(target_application_id);
```

#### `application_log_lines` (Rolling 500 lines per application)
```sql
CREATE TABLE application_log_lines (
    id           BIGSERIAL PRIMARY KEY,
    application_id    BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    line_number  BIGINT NOT NULL,              -- Monotonic per application
    content      TEXT    NOT NULL,
    captured_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_application_log_unique ON application_log_lines(application_id, line_number);
CREATE INDEX idx_application_log_latest ON application_log_lines(application_id, captured_at DESC);
```

#### `spring_session` (Spring Session JDBC)
```sql
-- Managed by Spring Session schema (Flyway V1.1)
```

### 3.2 Audit Action Types (Locked Enum)

```
START_APPLICATION
STOP_APPLICATION
CREATE_APPLICATION
DELETE_APPLICATION
ASSIGN_APPLICATION
RESET_PASSWORD
CREATE_USER
DELETE_USER
CREATE_ADMIN
LOGIN
LOGOUT
```

### 3.3 Constraints & Conventions
- **All timestamps:** `TIMESTAMPTZ` (UTC in DB)
- **Soft delete:** `deleted_at` on users/applications; all queries filter `WHERE deleted_at IS NULL`
- **Application names:** `^[a-zA-Z0-9_-]{1,64}$`, case-insensitive unique
- **Application IDs:** Never reused (supports audit log FK-less design)
- **SSH passwords:** Encrypted at rest via Jasypt (AES-256-GCM), never logged

---

## 4. API Contract

### 4.1 Conventions
- **Base path:** `/api/v1`
- **Pagination:** `page` (0-indexed), `size` (default 20, max 100), `sort=field,dir`
- **Error envelope:** RFC 7807 `ProblemDetail`
  ```json
  {
    "type": "https://example.com/errors/ACCESS_DENIED",
    "title": "Access Denied",
    "status": 403,
    "detail": "User not assigned to application BPL",
    "instance": "/api/v1/applications/1/start",
    "code": "ACCESS_DENIED",
    "details": { "applicationId": 1, "requiredRole": "USER" }
  }
  ```
- **Idempotency:** `Idempotency-Key` header required on `POST /applications/{id}/start|stop` (24h TTL, per user+application+key)

### 4.2 Error Codes

| Code | HTTP | Trigger |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | Bad username/password |
| `ACCESS_DENIED` | 403 | Role/assignment mismatch |
| `VALIDATION_FAILED` | 400 | DTO validation error |
| `SHELLCHECK_FAILED` | 400 | Script has ShellCheck `error` findings |
| `APPLICATION_ALREADY_RUNNING` | 409 | Start on RUNNING/STARTING |
| `APPLICATION_ALREADY_STOPPED` | 409 | Stop on STOPPED/STOPPING |
| `SSH_CONNECTION_FAILED` | 502 | Cannot reach server |
| `SSH_AUTH_FAILED` | 502 | SSH credentials rejected |
| `SSH_COMMAND_FAILED` | 502 | Script exited non-zero |
| `SELF_DELETE_FORBIDDEN` | 403 | Self-account deletion |
| `DUPLICATE_NAME` | 409 | User/application name conflict |
| `IDEMPOTENCY_CONFLICT` | 409 | Duplicate idempotency key |

### 4.3 Endpoints

#### Auth
| Method | Path | Roles | Description |
|---|---|---|---|
| POST | `/auth/login` | — | Login → sets HttpOnly cookie |
| POST | `/auth/logout` | All | Invalidate session |
| GET | `/auth/me` | All | Current user + assigned application IDs |

#### Engines
| Method | Path | Roles | Description |
|---|---|---|---|
| GET | `/applications` | All | List (status, startedAt only) |
| GET | `/applications/{id}` | Sys.Admin | Full detail (scripts, credentials masked) |
| POST | `/applications` | Sys.Admin | Create (ShellCheck validation) |
| PUT | `/applications/{id}` | Sys.Admin | Update (locked if not STOPPED) |
| DELETE | `/applications/{id}` | Sys.Admin | Delete (requires STOPPED) |
| POST | `/applications/{id}/start` | Assigned+ | Start (idempotent) |
| POST | `/applications/{id}/stop` | Assigned+ | Stop (idempotent) |
| GET | `/applications/{id}/logs` | Assigned+ | Last 500 lines (initial load) |

#### Users
| Method | Path | Roles | Description |
|---|---|---|---|
| GET | `/users` | Admin+ | List all |
| POST | `/users` | Admin+ | Create (role ≤ caller ceiling) |
| PUT | `/users/{id}` | Admin+ | Update role + assignments (combined) |
| DELETE | `/users/{id}` | Admin+ | Delete (not self) |
| POST | `/users/{id}/reset-password` | Admin+ | Returns temp password, sets `must_change_password=true` |

#### Audit Logs
| Method | Path | Roles | Description |
|---|---|---|---|
| GET | `/audit-logs` | Admin+ | Filter: `from`, `to`, `page`, `size` |

---

## 5. WebSocket Protocol

### 5.1 Connection
- **Endpoint:** `/ws` (STOMP over native WebSocket, no SockJS)
- **Auth:** HttpOnly session cookie (same as REST)
- **Heartbeat:** 10s client → server, 10s server → client

### 5.2 Topics
| Topic | Subscribers | Payload |
|---|---|---|
| `/topic/application-logs/{applicationId}` | Assigned users + Admin+ | `{ lineNumber, content, capturedAt }` |
| `/topic/audit-log` | Admin+ only | Audit log row (same as REST) |

### 5.3 Subscription Rules
- Frontend subscribes to currently selected log dropdown entry
- Dropdown switch → unsubscribe old, subscribe new
- **Reconnect:** Exponential backoff 1/2/4/8/16/30s (max 30s), 10 attempts, then show banner

### 5.4 Limits
| Limit | Value |
|---|---|
| Per-user connections | 5 |
| Per-application subscribers | 50 |
| Global connections | 500 |

---

## 6. Application Lifecycle

### 6.1 Status States
```
STOPPED → STARTING → RUNNING
    ↑                    ↓
    ←←←← STOPPING ←←←←←←
    ↑                    ↓
    ←←←←←← ERROR ←←←←←←←
```

### 6.2 Start Flow
1. Validate: application STOPPED, user assigned, idempotency key unused
2. Set status = `STARTING`, `started_at = NULL`
3. SSH: connect (10s timeout) → run start_script (120s timeout)
4. On success: status = `RUNNING`, `started_at = NOW()`, start log poller
5. On failure: status = `ERROR`, write audit `FAILURE`, stop poller if running
6. Broadcast status change via WebSocket (future enhancement)

### 6.3 Stop Flow
1. Validate: application RUNNING, user assigned, idempotency key unused
2. Set status = `STOPPING`
3. SSH: connect → run stop_script (120s timeout)
4. On success: status = `STOPPED`, `started_at` preserved, stop log poller
5. On failure: status = `ERROR`, write audit `FAILURE`
6. Write "Log polling stopped" to application log stream

### 6.4 Edit/Delete Locks
- **PUT `/applications/{id}`** → 403 if status ≠ `STOPPED`
- **DELETE `/applications/{id}`** → 409 `APPLICATION_ALREADY_RUNNING` if status ≠ `STOPPED`

---

## 7. Log Pipeline

### 7.1 Poller Behavior
- **One poller per application** (only when status = `RUNNING`)
- **Interval:** Per-application `poll_interval_seconds` (default 5s)
- **On status → RUNNING:** Immediate poll, then schedule interval
- **On status → STOPPED/ERROR:** Cancel poller immediately

### 7.2 Deduplication Algorithm
1. Run `log_script` via SSH (15s timeout) → ordered lines (oldest→newest)
2. Each line prefixed with epoch ms: `1725638400123 [INFO] message`
3. Recall last saved `content` for this application
4. Search new batch **backward** for matching content
5. Lines after match = new → append with monotonic `line_number`, trim to 500
6. **Fallback:** If last content not found → treat entire batch as new
7. Broadcast new lines via WebSocket to `/topic/application-logs/{applicationId}`

### 7.3 Log Script Template (Default)
```bash
#!/bin/bash
LOG_FILE="${LOG_FILE:-/var/log/bpl-application.log}"
LINES="${LINES:-500}"
tail -n "$LINES" "$LOG_FILE" 2>/dev/null | awk '{printf "%d %s\n", systime()*1000, $0}'
```

### 7.4 SSH Failure Handling
- Connection/auth failure → write `[ERROR] Log poll failed: <reason>` to application log stream
- Invalidate cached SSH connection → lazy reconnect on next tick
- No silent retries, no swallowing

---

## 8. Security

### 8.1 Authentication
- **Session-based** (Spring Session JDBC → PostgreSQL)
- **Cookie:** `HttpOnly`, `Secure` (prod), `SameSite=Strict`, `Path=/`, 8h TTL
- **Password hash:** Argon2id (Spring Security default)
- **Must-change-password:** Forced on first login after creation/reset

### 8.2 Authorization
- **Method-level:** `@PreAuthorize` on controllers
- **Application assignment check:** Custom `SecurityExpressionRoot` method `canAccessApplication(id)`
- **Admin ceiling:** Enforced in service layer (Admin cannot create Admin/Sys.Admin)

### 8.3 Encryption
- **SSH passwords:** Jasypt field-level encryption (AES-256-GCM)
- **Jasypt key:** 32+ char random, from Vault/secrets manager
- **TLS:** TLS 1.3 only, terminated at Caddy

### 8.4 CORS
- **Dev:** `http://localhost:5173`, credentials=true
- **Prod:** Single origin from env, credentials=true
- **No wildcard** with credentials

### 8.5 CSP (Frontend)
```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' data:;
font-src 'self';
connect-src 'self' wss:;
frame-ancestors 'none';
base-uri 'self';
form-action 'self'
```

### 8.6 Rate Limiting
- **Login:** 10 req/s per IP (Caddy)
- **API:** Bucket4j per-user + per-IP (configurable)
- **WS:** Connection limits (§5.4)

---

## 9. Frontend Architecture

### 9.1 Tech Stack
- React 18 + TypeScript + Vite
- React Router v6 (lazy routes by role)
- TanStack Query v5 (server state)
- `@stomp/stompjs` (WebSocket)
- React Hook Form + Zod (forms)
- Tailwind CSS (JIT) + Headless UI
- `react-window` (virtualized log viewer)

### 9.2 Routes (Role-Gated)
| Route | Roles | Component |
|---|---|---|
| `/` (Dashboard) | All | Dashboard |
| `/applications` | Sys.Admin | Applications CRUD |
| `/users` | Admin+ | Users management |
| `/login` | — | Login |
| `/change-password` | All (forced) | Password change |

### 9.3 Dashboard Layout
- **Top tab bar:** Role-gated visibility
- **Application grid:** Cards (name, status badge, start/stop buttons, Time Started, Running Time)
- **Logs box (embedded):** Dropdown → application logs + Audit Log (Admin+ only)
  - Auto-follow bottom by default
  - Manual scroll up → pauses auto-follow, shows "↓ Jump to latest"
  - Dropdown switch → resets to bottom, re-enables auto-follow

### 9.4 Empty States
- **User, 0 applications:** "No applications assigned. Contact your admin."
- **Sys.Admin, 0 applications:** Message + "Add Application" CTA

### 9.5 Access Denial
- Direct navigation to unauthorized route → 403 page (not redirect)

---

## 10. Project Structure

```
BPL-Order-Application-Admin/
├── .claude/                 # Claude Code config (hooks, skills, agents)
├── backend/                 # Spring Boot 3.3 + Java 21 + Gradle
│   ├── src/main/java/...    # Application code
│   ├── src/main/resources/  # application.yml, db/migration/
│   ├── build.gradle.kts
│   └── Dockerfile
├── frontend/                # React 18 + TypeScript + Vite
│   ├── src/                 # Components, hooks, pages
│   ├── package.json
│   ├── vite.config.ts
│   └── Dockerfile
├── docker-compose.yml       # Dev/prod orchestration
├── Caddyfile                # TLS termination, security headers
├── SPEC.md                  # This file
└── .gitignore
```

---

## 11. Infrastructure

### 13.1 Docker Compose (Production)
```yaml
services:
  postgres:     postgres:16-alpine (SSL, scram-sha-256)
  backend:      Distroless Java 21, read-only, non-root
  frontend:     Nginx + Brotli, read-only
  caddy:        TLS termination, security headers, rate limit
```

### 13.2 Secrets (External)
| Secret | Rotation |
|---|---|
| `DB_PASSWORD` | 90 days |
| `JASYPT_PASSWORD` | 180 days |
| `KEYSTORE_PASSWORD` | 180 days |
| TLS certs | 90 days (Caddy auto) |

### 13.3 Observability (Separate Stack)
- **Metrics:** `/actuator/prometheus` (Micrometer)
- **Logs:** JSON stdout → Loki via promtail
- **Traces:** OTLP → Tempo (optional)

---

## 12. Operational Procedures

### 13.1 First Sys.Admin Bootstrap
1. Flyway migration inserts seeded Sys.Admin
2. Initial password shown once at deploy (logs/secret)
3. First login → forced password change

### 13.2 Password Reset (Admin → User)
1. Admin calls `POST /users/{id}/reset-password`
2. Backend returns `{ "temporaryPassword": "..." }` (once, HTTPS)
3. Admin delivers via existing comms (Slack/email/verbal)
4. User logs in → forced change

### 13.3 Application Creation (Sys.Admin)
1. Fill form: name, IP, SSH user, password, host key fingerprint, scripts, poll interval
2. ShellCheck runs (CI: fail on `error`; dev: warn if binary missing)
3. Save → encrypt password, store fingerprint, start poller if RUNNING

### 12.4 Backup/Restore
- **Application:** None (Flyway forward-only)
- **Database:** Infra responsibility (pg_dump / WAL-G / managed PG)

---

## 13. Configuration Reference

### 13.1 Backend (application-prod.yml)
```yaml
server:
  port: 8443
  ssl:
    enabled: true
    protocol: TLSv1.3
spring:
  session:
    jdbc:
      initialize-schema: never
    timeout: 8h
    cookie:
      secure: true
      http-only: true
      same-site: strict
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 5000
  flyway:
    baseline-on-migrate: true
    validate-on-migrate: true
    out-of-order: false
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
logging:
  pattern:
    console: '{"ts":"%d{ISO8601}","lvl":"%level","tid":"%X{traceId}","sid":"%X{spanId}","logger":"%logger","msg":"%msg"}%n'
```

### 13.2 SSH Timeouts (Per Application, Configurable)
| Operation | Default |
|---|---|
| Connect | 10s |
| Start/Stop command | 120s |
| Log command | 15s |

### 13.3 WebSocket Limits
```yaml
websocket:
  max-per-user: 5
  max-per-application: 50
  max-global: 500
```

---

## 14. Out of Scope (v1)

- Automated email/SMS notifications
- Independent application health checks (status is UI-driven only)
- Database backup/restore tooling
- Horizontal scaling (single backend instance)
- PCL application implementation (factory pattern ready)
- SSO / LDAP / OAuth
- Multi-language support
- Dark mode (Tailwind `dark:` classes ready, not implemented)

---

## 15. Acceptance Criteria (v1 Demo)

- [ ] Sys.Admin logs in, creates application (ShellCheck passes), starts it, sees live logs
- [ ] Sys.Admin creates Admin, Admin creates User, assigns application
- [ ] User logs in, sees only assigned application, can start/stop, views logs
- [ ] Admin views audit log with date filter
- [ ] WebSocket reconnects after backend restart
- [ ] Double-click Start → single execution (idempotency)
- [ ] Edit application while RUNNING → 403
- [ ] Delete application while RUNNING → 409
- [ ] Self-delete → 403
- [ ] All timestamps UTC in DB, local in UI
- [ ] CSP headers present, no console errors
- [ ] Distroless backend image < 150MB

---

## 16. Future Enhancements (Post-v1)

1. **Health check script** per application (independent status verification)
2. **PCL application** via factory pattern
3. **Scheduled start/stop** (cron expressions)
4. **Log retention policy** (beyond 500 lines)
5. **Webhook notifications** (Slack, PagerDuty)
6. **Application metrics** (CPU, memory via SSH)
7. **Multi-instance backend** (Redis session store, WS sticky sessions)
8. **Dark mode** toggle

---

*End of Specification*