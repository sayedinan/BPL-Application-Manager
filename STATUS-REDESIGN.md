# Live Status Checking — Design Doc

**Status:** Agreed in planning chat, not yet built.
**Supersedes:** the `applications.status` column and the RUNNING/STOPPED/STARTING/STOPPING/ERROR model described in SPEC.md §3.1/§3.2/§6.

---

## 1. Why we're doing this

Today, `applications.status` is a **memory of the last action taken**, not a
measurement. `POST /applications/{id}/start` succeeds → we write `RUNNING`
and trust it forever. If the process dies on the remote server (crash, OOM,
killed by something else on a shared server) or someone starts/stops it
manually outside the app, the DB — and every user's dashboard — keeps
showing the old value indefinitely. That's a real gap for a tool whose
core value proposition is "tells you the true state of your applications."

This was previously a documented, accepted v1 limitation
(PLANNING-NOTES.md §6.2: "purely UI-driven... intentional limitation";
SPEC.md §16: "health check script per application" listed under Future
Enhancements). This doc records the decision to pull it into scope now,
and what that actually changes.

---

## 2. Core decisions

| Question | Decision |
|---|---|
| How is status measured? | 4th Sys.Admin-authored script (`status_script`), run over SSH — not an HTTP health endpoint, since apps aren't guaranteed to be web servers (systemd unit, Docker, bare process, etc.) |
| SSH connection | **Separate** cached connection per application, independent of the log poller's connection — different schedule, shouldn't contend |
| Poll interval | Hardcoded, shortest practical — **not** a user-configurable field, no new UI |
| Crash detection state | No new `ERROR`/`CRASHED` state — checker just reports the real state directly (online or offline) |
| Reconciliation direction | **Two-way** — catches both "thought running, actually dead" and "thought stopped, actually running" (started manually outside the app) |
| Pause during actions | Yes — in-memory `actionInProgress[appId]` flag skips that app's poll tick while its start/stop script is executing; cleared when the script finishes, not persisted |
| Persisted states | Only **online** / **offline**. No STARTING/STOPPING/ERROR in the DB — those are pure frontend loading UX (spinner) while a start/stop request is in flight |
| Button behavior | Driven by actual derived state, not stored intent: online → shows Stop; offline → shows Start |
| Live updates across clients | **WebSocket broadcast**, one global topic `/topic/application-status`, payload `{applicationId, online, transitionedAt}`. Reconnect-backoff-then-banner (same pattern as the log viewer) is the fallback safety net for a disconnected client — no polling fallback for v1 |

---

## 3. Start/Stop flow (new)

1. User clicks Start (or Stop) → UI shows loading.
2. Backend sets `actionInProgress[appId] = true`, runs the script over SSH.
3. Script finishes (success or fail) → `actionInProgress[appId] = false`.
4. Backend immediately polls status (via the dedicated status connection)
   in a bounded wait loop until it confirms the expected state, or times out:
   - **Start → wait for online, timeout 30s**
   - **Stop → wait for offline, timeout 15s**
5. On confirmed flip: broadcast the transition over WebSocket, return
   success, loading clears.
6. On timeout: return **`STATUS_CONFIRMATION_TIMEOUT`** (HTTP 504) —
   distinct from `SSH_COMMAND_FAILED` (the SSH command itself succeeded;
   reality just didn't confirm within the window). Loading clears, error
   shown. The background poller keeps running and will catch the real
   state whenever it actually settles, even after the timeout.

---

## 4. Uptime / statistics table (separate from audit log)

Audit log already tracks **who** started/stopped an app (actor, action,
timestamp) — this new mechanism is purely about **state over time**, not
attribution.

- **Insert-only transition log**: `(application_id, state, transitioned_at)`,
  one row per detected flip.
- **Running "current streak start" pointer**, maintained alongside the log,
  for cheap "how long has it been in this state" lookups without
  rescanning/aggregating the full log every time.
- On application creation: no stored `status`. Treated as offline by
  default, but the status checker runs **immediately** after creation to
  get a real first reading rather than waiting for the next scheduled tick.
- **"Since deploy"** = since the application row was created in the BPL DB
  (not since the remote process first started on its server — we have no
  way to know that).
- From the log + pointer, derive: total uptime since deploy, total
  downtime since deploy, current online streak, current offline streak.
- **Cascade-deletes with the application** (same pattern as
  `application_log_lines`) — unlike `audit_logs`, which deliberately never
  cascades and never touches deleted-application references.
- No debounce for v1 (flagged, not decided): a crash-looping app could
  write a transition row on every flip and make uptime stats noisy. Revisit
  if it becomes a real problem.

---

## 5. What this replaces / ripples into

- **`V2__applications.sql`** — drop `status`, `started_at`, and the status
  CHECK constraint. New migration(s) add `status_script` and the
  transition-log + streak-pointer tables.
- **`ApplicationController`** — remove all `status='...'` writes;
  `/start` and `/stop` become "run script → poll-until-confirmed → return
  (or timeout)."
- **`LogPollerScheduler`** — currently gated on `status = RUNNING`
  (SPEC §7.1). Must instead gate on the *derived* online/offline value,
  coupling the log poller's on/off switch to the status poller's output.
- **RBAC / dashboard badge logic** — the existing three-way badge model
  (green RUNNING / gray STOPPED+ERROR / yellow transitional) collapses to
  two-way (online/offline) plus a client-local loading spinner for
  in-flight actions.
- **PUT/DELETE application locks** (SPEC §6.4 — can't edit/delete unless
  STOPPED) — recheck against the derived online/offline value instead of
  the old column.
- **Frontend**: `StartStopHook.ts`, `Dashboard.tsx` badge rendering,
  `ApplicationsPage.tsx` lock logic, new WebSocket subscription to
  `/topic/application-status` alongside the existing log/audit topics.
- **Cleanup on stop/delete**: now closes **two** cached SSH connections
  per application (log + status), not one — the
  `ssh-connection-handling` skill's cleanup rule needs updating to reflect
  this.
- **Existing `status` data**: dropping the column means any current
  values disappear. Acceptable — that data was never trustworthy under
  the old model anyway — but noted so it's a deliberate migration, not a
  surprise.

---

## 6. Explicitly deferred / not decided

- Debounce for flapping/crash-looping apps (transition log noise).
- Polling fallback for WebSocket-disconnected clients (decided against
  for v1 — reconnect-with-banner is the only safety net).
