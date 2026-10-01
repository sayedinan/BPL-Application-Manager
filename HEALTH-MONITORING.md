# HEALTH-MONITORING.md

Source of truth for the application health-monitoring feature.
Migrations V20+ and the `com.bpl.orderapp.admin.health` package cite this file.
Companion docs: `SPEC.md`, `STATUS-REDESIGN.md`, `HEALTH_CONTRACT.md` (the JSON each app returns).

## 1. Purpose

Show business-side viewers a visual health card per managed application,
and use the application's own health API as the signal for online/offline.

## 2. Decisions (locked)

| # | Decision |
|---|---|
| D1 | Each app may expose a health URL returning either the standard contract (`HEALTH_CONTRACT.md`, format `CONTRACT`) or Spring Boot Actuator health JSON (format `ACTUATOR`, mapped by an adapter). |
| D2 | API keys are stored per app, encrypted with the existing `SshCredentialCipher`, never returned by any endpoint. Only SYS_ADMIN can set or change them. |
| D3 | The health poller replaces the SSH `status_script` poller for any app that has an enabled health config. Apps without one keep using `status_script` (fallback). |
| D4 | Online/offline transitions detected by the health poller write the same audit rows (`APPLICATION_ONLINE` / `APPLICATION_OFFLINE`) and send email/SMS through the existing `List<Notifier>`. |
| D5 | Alerts fire only for UP and DOWN transitions, never for DEGRADED. |
| D6 | No "skip certificate verification" option exists. |
| D7 | SSL certificate expiry is read from the same TLS connection the poller uses. |

## 3. Online/offline mapping

| Observation | Result |
|---|---|
| Reachable, HTTP 200, status `UP` | online |
| Reachable, HTTP 200, status `DEGRADED` | online (card shows amber, no alert) |
| Status `DOWN`, timeout, connection error, non-200, invalid JSON, TLS failure | offline |
| Status `UNKNOWN` | no change: keep the previous online/offline state, no transition, no alert |

An app must fail 2 consecutive polls before it is declared offline (constant
`OFFLINE_AFTER_FAILURES = 2`), so one dropped request does not raise an alert.
The existing flap suppression (3+ transitions in 2 minutes) still applies.

## 4. Data model (migration V20)

All three tables cascade-delete with the application, same pattern as V5 and V12.

### `application_health_config`
One optional row per application. No row = no health monitoring (SSH fallback).

| Column | Type | Notes |
|---|---|---|
| `application_id` | BIGINT PK, FK -> applications ON DELETE CASCADE | |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | |
| `url` | TEXT NOT NULL | Full health URL. `http://` = plain; `https://` = TLS. |
| `format` | VARCHAR(16) NOT NULL | `CONTRACT` or `ACTUATOR` |
| `api_key_enc` | TEXT NULL | Encrypted with `SshCredentialCipher`. NULL = no key sent. |
| `tls_pin_sha256` | VARCHAR(64) NULL | For HTTPS with a self-signed cert: hex SHA-256 of the certificate. NULL = normal verification. |
| `poll_interval_seconds` | INT NOT NULL DEFAULT 10 | Check 3 to 300. |
| `created_at`, `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |

### `application_health_latest`
One row per application, overwritten on every poll. Holds what the card shows.

| Column | Type | Notes |
|---|---|---|
| `application_id` | BIGINT PK, FK CASCADE | |
| `checked_at` | TIMESTAMPTZ NOT NULL | |
| `reachable` | BOOLEAN NOT NULL | |
| `http_status` | INT NULL | |
| `response_ms` | INT NULL | |
| `status` | VARCHAR(16) NULL | `UP`, `DEGRADED`, `DOWN`, `UNKNOWN` |
| `payload` | JSONB NULL | Last good response, already normalized to the contract shape. |
| `error` | TEXT NULL | Short reason when the check failed. |
| `ssl_not_after` | TIMESTAMPTZ NULL | Certificate expiry, HTTPS only. |
| `consecutive_failures` | INT NOT NULL DEFAULT 0 | Drives `OFFLINE_AFTER_FAILURES`. |

### `application_health_history`
Compact, one row per poll. No payload, so size stays small.

| Column | Type |
|---|---|
| `id` | BIGSERIAL PK |
| `application_id` | BIGINT NOT NULL, FK CASCADE |
| `checked_at` | TIMESTAMPTZ NOT NULL |
| `reachable` | BOOLEAN NOT NULL |
| `response_ms` | INT NULL |
| `status` | VARCHAR(16) NULL |

Index: `(application_id, checked_at DESC)`.
Retention: 30 days, cleaned up by a daily job. At a 10-second interval that is
about 260,000 rows per app for 30 days.

## 5. Reused, not duplicated

- `application_status_transitions` and `application_status_streak` (V12) stay the
  single record of online/offline flips and uptime. The health poller writes to them
  through the same reconcile logic as the SSH poller.
- `LifecycleEventType.EXTERNAL_ONLINE` / `EXTERNAL_OFFLINE` and the existing
  email/SMS templates are reused. No new event types.
- Audit action types are unchanged. The audit `detail` gets `"detectedBy": "HEALTH_API"`.
- RBAC: no new `Action`. Config create/update uses `UPDATE_APPLICATION` (SYS_ADMIN).
  Viewing health uses `LIST_APPLICATIONS` plus `canAccessApplication`.

## 6. Out of scope for step 2

The poller, the Actuator adapter, the pin/SSL logic, the REST endpoints and the
frontend. They follow in steps 3, 4 and 5.

## 7. Open items

- Whether the first app uses HTTP, HTTPS with a trusted certificate, or a
  self-signed certificate (decides whether `tls_pin_sha256` is used first).
- `OFFLINE_AFTER_FAILURES` and the 10-second default interval are starting values.
