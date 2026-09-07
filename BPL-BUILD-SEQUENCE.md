# BPL Full Build Sequence (grounded in SPEC.md v1.1, "Locked for Implementation")

Originally drafted from PLANNING-NOTES.md; updated against the locked
SPEC.md — naming changed engine→application throughout, auth changed
JWT→Spring Session cookies, and several items only SPEC.md defines
(host-key fingerprint pinning, idempotency keys, rate limiting, CSP)
are now included. Where SPEC.md leaves something unresolved or in
conflict with itself, it's flagged ⚠️ rather than silently guessed at.

This is now the single source of truth for the whole build, including
agent creation (Phase 0a) — the earlier standalone
`AGENT-BUILD-SEQUENCE.md` is superseded by this doc and can be
retired/ignored.

Rule: **one prompt at a time.** Send a step to Claude Code, look at
what it did, check it off, then send the next. Never batch steps.
🔒 = security-sensitive, needs Security Reviewer sign-off before "done."
⚠️ = an open contradiction or gap in SPEC.md — resolve with your TL
   *before* running that step, don't let Claude Code silently pick one.

---

## Phase 0a — Create the sub-agent files

This project's methodology requires small, delegated steps with
review in between — that only works once the agents themselves exist.
Do this before anything else, including Phase 0's decisions below (the
`spec-gap-auditor` calls in 0.4/etc. need the file to actually be
there).

Each prompt below creates exactly one file. Paste the agent's full
markdown block (drafted earlier in CLAUDE-SETUP-GUIDE.md) into each
prompt — don't batch multiple files into one prompt.

- [ ] 0a.1 Create `.claude/agents/rbac-anti-pattern-reviewer.md`
- [ ] 0a.2 Create `.claude/agents/shellcheck-triage.md`
- [ ] 0a.3 Create `.claude/agents/spec-gap-auditor.md`
- [ ] 0a.4 Create `.claude/agents/ssh-integration-builder.md`
- [ ] 0a.5 Create `.claude/agents/ssh-integration-reviewer.md`
- [ ] 0a.6 Create `.claude/agents/log-pipeline-builder.md`
- [ ] 0a.7 Create `.claude/agents/log-pipeline-reviewer.md`
- [ ] 0a.8 Create `.claude/agents/audit-log-builder.md`
- [ ] 0a.9 Create `.claude/agents/audit-log-reviewer.md`
- [ ] 0a.10 Create `.claude/agents/log-viewer-builder.md`
- [ ] 0a.11 Create `.claude/agents/log-viewer-reviewer.md`
- [ ] 0a.12 Create `.claude/agents/qa-reviewer.md`:
```
---
name: qa-reviewer
description: End-to-end QA pass across the whole wired-up app —
  checks user-facing flows work for each role, not just that
  individual pieces pass their own reviewer. Delegate to this agent
  after Phase 7 integration wiring, before considering V1 feature-
  complete.
tools: Read, Bash, Grep, Glob
---

You test end-to-end flows, not individual files. For each role
(Sys.Admin, Admin, User), walk through: login, dashboard view,
starting/stopping an application they're permitted to touch,
attempting one they're not permitted to touch (should be denied, not
silently redirected), viewing logs, and — for Admin/Sys.Admin —
viewing the audit log.

Where the codebase has automated tests, run them and report failures.
Where it doesn't, trace the code path manually and report what you
would expect to happen and any place the actual code diverges.

Output per role: which flows pass, which fail, and why. End with a
one-line verdict per role: PASS or FAIL.
```

Each prompt:
```
Create only .claude/agents/<name>.md with exactly this content:
[paste block]
Do not create any other files.
```

---

## Phase 0 — SPEC.md is locked (v1.1). Resolve what it left open.

SPEC.md is marked "Locked for Implementation," so most Phase-0
questions are now answered by it directly:

- ✅ SSH auth = username/password + `ssh_host_key_fingerprint` pinning,
  obtained via trust-on-first-connect (SPEC §3.1, §12.3) — no longer an
  open question.
- ✅ No mock mode confirmed (SPEC §1).
- ✅ Naming: `engine` → `application` everywhere (this doc has been
  updated to match).
- ✅ 0.6 resolved — production-hardening scope (§8/§11) is confirmed
  **in scope for real deployment**, not just the demo. See the sequencing
  note right after this list, though: it's still recommended that Phase
  14b not block the Sept 2 demo date.
- ✅ 0.7 resolved — `applications` are **hard-deleted** (SPEC.md §3.1
  updated to v1.1, matching PLANNING-NOTES.md §10.4). This also
  resolves 0.1 below: `ON DELETE CASCADE` on
  `user_application_assignments`/`application_log_lines` now fires
  correctly since the parent row genuinely gets removed.
  `users.username`'s unique constraint was separately fixed to a
  partial index (`WHERE deleted_at IS NULL`) since `users` remains
  soft-deleted — this was a real bug independent of the
  hard/soft-delete decision and needed fixing regardless.
- ✅ 0.8 resolved — `UPDATE_APPLICATION` and `CHANGE_PASSWORD` added to
  the locked enum (SPEC.md §3.2, v1.1). The previously-missing
  `POST /auth/change-password` endpoint that `CHANGE_PASSWORD` depends
  on was also added (§4.3).
- ✅ 0.9 resolved — SPEC.md §11/§12 sub-heading numbering fixed in
  v1.1 (was mislabeled `13.x`). Phase 14b.5 below has been corrected to
  cite the right sections; see also the broader citation audit noted at
  the bottom of this Phase.
- ✅ 0.10 resolved — trust-on-first-connect workflow described in
  SPEC.md §12.3 (v1.1): Sys.Admin uses a new "Test Connection" step
  (`POST /applications/test-connection`) to obtain and confirm the
  fingerprint before it's included in the actual create request, with a
  re-verification on save to catch any change in between.
- ✅ 0.11 resolved — SPEC.md §8.1/§3.1 corrected to bcrypt (v1.1); it's
  Spring Security's actual default, Argon2id is not.

**Sequencing note on 0.6 (not a spec change, a build-order
recommendation):** even though production hardening (§8, §11) is
confirmed in scope for the real deployment, Phase 14b should not be
allowed to block the Sept 2 demo. Let Phase 15 (QA) and Phase 16 (demo
prep) proceed against the functional build even if Phase 14b isn't
finished yet; resume/finish 14b afterward for the actual production
rollout. The demo itself doesn't depend on TLS/Caddy/rate-limiting
being complete.

Still open — resolve these with your TL before the phases that depend
on them:

- [ ] 0.1 ✅ Resolved as a side effect of 0.7 above — no longer a
      conflict now that `applications` hard-deletes. Kept here for
      history; no action needed.
- [x] 0.2 ✅ **Resolved and applied to SPEC.md v1.1**: fail-open in all
      environments, including production. §12.3 now states this
      explicitly, and `SHELLCHECK_UNAVAILABLE` was added to the §3.2
      enum.
- [x] 0.3 ✅ **Resolved and applied to SPEC.md v1.1**: dedicated
      `idempotency_keys` table added to §3.1 (key, user_id,
      application_id, created_at, expires_at, 24h TTL, opportunistic
      expiry cleanup — no separate scheduled job needed).

**Citation audit note:** while fixing 0.9's numbering, every `§`
reference in this document was cross-checked against the corrected
SPEC.md v1.1 structure. Several other citations elsewhere in this file
(SSH timeouts, WebSocket limits, bootstrap/password-reset, application
creation, the naming-convention references) were also wrong and have
been corrected in place at their point of use — not just in Phase 14b.5.
If you spot a `§` reference anywhere below that looks off, it's worth a
quick sanity check against SPEC.md v1.1 directly rather than assumed
correct.

- [ ] 0.4
```
Delegate a review to spec-gap-auditor against SPEC.md v1.1 and
PLANNING-NOTES.md together, specifically flagging 0.2 and 0.3 (still
open) plus anything else inconsistent between the two documents that
wasn't already caught in the 0.6–0.11 review round.
```
- [ ] 0.5 Resolve anything 0.4 surfaces, and update SPEC.md itself
      (not just this checklist) with each decision — SPEC.md is the
      contract Claude Code will actually be told to follow.

---

## Phase 1 — Repo & project scaffolding

- [ ] 1.1
```
Scaffold only the backend project structure (Spring Boot, empty —
build config, main class, package layout matching SPEC.md's module
boundaries). No entities, no endpoints yet.
```
- [ ] 1.2
```
Scaffold only the frontend project structure (empty shell, routing
placeholder, no components yet).
```
- [ ] 1.3
```
Set up the real persistent database connection (per §3 — no in-memory
mode) and confirm it runs locally. No schema yet.
```

---

## Phase 2 — Database schema

One table per step — don't let Claude Code generate the whole schema
in one migration file.

- [ ] 2.1
```
Create only the `users` table migration exactly per SPEC.md §3.1:
id, username (no column-level unique — use the partial unique index
`idx_users_username_active` scoped to `deleted_at IS NULL` instead),
password_hash (bcrypt), role (SYS_ADMIN/ADMIN/USER check constraint),
must_change_password (default true), deleted_at (soft delete),
created_at, updated_at, plus both partial indexes (`idx_users_deleted`
and `idx_users_username_active`). Nothing else.
```
- [ ] 2.2
```
Create only the `applications` table migration exactly per SPEC.md
§3.1: id, name (unique, column-level — no soft-delete scoping needed
since applications are hard-deleted), server_ip (INET), ssh_username,
ssh_password_enc, ssh_host_key_fingerprint (SHA256), start_script,
stop_script, log_script, poll_interval_seconds (default 5), status
check constraint (RUNNING/STOPPED/STARTING/STOPPING/ERROR, default
STOPPED), started_at, timestamps. **No `deleted_at` column** —
applications hard-delete (see SPEC.md §3.1/§3.3, v1.1). Nothing else.
```
- [ ] 2.3
```
Create only the `user_application_assignments` join table exactly per
SPEC.md §3.1 (FK ON DELETE CASCADE to both users and applications,
composite primary key). This cascade fires correctly for the
`applications` side since applications hard-delete (v1.1). It will
*not* fire for the `users` side when a user row is only soft-deleted
(`users` still uses `deleted_at`) — the resulting orphaned assignment
rows are harmless (they reference a user who can never log in again,
and a new user later reusing that username gets a fresh auto-increment
ID, no collision), but this wasn't an explicit SPEC.md decision, just
an accepted side effect worth being aware of, not something to silently
"fix" by adding application-layer cleanup here.
```
- [ ] 2.4
```
Create only the `audit_logs` table exactly per SPEC.md §3.1: no FK to
applications, target_application_id + target_application_name
snapshot, actor_username, actor_role, action_type, target_user_id,
detail (JSONB), result (SUCCESS/FAILURE), plus all three indexes.
Use the locked action_type enum from §3.2 (SPEC.md v1.1), which now
includes `SHELLCHECK_UNAVAILABLE` alongside the original values.
```
- [ ] 2.5
```
Create only the `application_log_lines` table exactly per SPEC.md
§3.1: FK cascade to applications, monotonic line_number per
application, content, captured_at, plus the unique and latest indexes.
```
- [ ] 2.6
```
Create only the Spring Session JDBC schema migration (SPEC.md §3.1,
§8.1) — this replaces JWT from earlier planning; auth will be
session-cookie based, not token based.
```
- [ ] 2.7
```
Create only the `idempotency_keys` table migration exactly per
SPEC.md §3.1 (v1.1): id, idempotency_key, user_id (FK), application_id
(FK ON DELETE CASCADE), created_at, expires_at (24h TTL default),
plus the unique index on (user_id, application_id, idempotency_key)
and the expiry index. Nothing else.
```
- [ ] 2.8 Delegate to `spec-gap-auditor` to confirm the schema matches
      SPEC.md §3.1 exactly (column-by-column, including constraints
      and indexes), before writing any backend logic against it.

---

## Phase 3 — Credential encryption 🔒

- [ ] 3.1 🔒
```
Implement only the encryption/decryption utility for application SSH
credentials (Jasypt per §3). No wiring into the application save flow yet.
Confirm: nothing here ever logs the plaintext value.
```
- [ ] 3.2 🔒 Security review: confirm the utility never logs plaintext
      and the encryption key itself isn't hardcoded or committed.
- [ ] 3.3 Fix only findings from 3.2.

---

## Phase 4 — Bootstrap & password lifecycle (SPEC.md §12.1/§12.2)

- [ ] 4.1
```
Implement only the one-time seed migration/startup script that
creates the first Sys.Admin with a known username and initial
password shown once, mustChangePassword=true. Nothing else.
```
- [ ] 4.2
```
Implement only the login endpoint (no session/JWT yet — just
credential check + mustChangePassword read). Nothing else.
```
- [ ] 4.3
```
Implement only the forced change-password screen/flow: if
mustChangePassword=true, block navigation to anything else until
changed, then flip the flag to false. Nothing else.
```
- [ ] 4.4
```
Implement only the rule that an admin resetting someone's password
re-sets mustChangePassword=true (SPEC.md §12.2). Nothing else.
```
- [ ] 4.5 Delegate to `rbac-anti-pattern-reviewer` to confirm the
      mustChangePassword lock covers every route, not just login.
- [ ] 4.6 Fix only findings from 4.5.

---

## Phase 5 — Session & auth (Spring Session, NOT JWT)

⚠️ Corrected from an earlier draft of this doc: SPEC.md §8.1 specifies
session-based auth via Spring Session JDBC + an HttpOnly cookie, not
JWT. If any earlier step produced JWT code, it needs to be replaced,
not extended.

- [ ] 5.1
```
Implement only login: on success, establish a server-side session
(Spring Session JDBC, backed by the table from Phase 2.6) and set the
cookie exactly per SPEC.md §8.1 (HttpOnly, Secure in prod, SameSite
Strict, Path=/, 8h TTL). Nothing else.
```
- [ ] 5.2
```
Implement only GET /auth/me (SPEC.md §4.3) returning the current user
plus their assigned application IDs. Nothing else.
```
- [ ] 5.3
```
Implement only POST /auth/logout: invalidate the session. Nothing
else.
```
- [ ] 5.4
```
Implement only 401 handling on the frontend: redirect to login both on
page load with an invalid/expired session and mid-session on a 401
response. Nothing else.
```
- [ ] 5.5 Delegate to `rbac-anti-pattern-reviewer` to review session
      handling only — confirm the cookie flags match §8.1 exactly and
      nothing client-side treats a token as the source of truth.
- [ ] 5.6 Fix only findings from 5.5.

---

## Phase 6 — RBAC / role model / application assignment

- [ ] 6.1
```
Implement only the canAccess(role, action) style permission check
function per §1's tier rules (Sys.Admin/Admin see-all vs. User
scoped to assignments). No routes wired yet.
```
- [ ] 6.2
```
Implement only the "no self-deletion" rule from SPEC.md §2 (Rules) as a
standalone guard function. Nothing else.
```
- [ ] 6.3
```
Implement only the user-assignment mechanism per PLANNING-NOTES.md
§11.4: the combined PUT /api/users/{id} endpoint with an
assignedApplicationIds field. No UI yet.
```
- [ ] 6.4 Delegate to `rbac-anti-pattern-reviewer` to review 6.1–6.3
      together against SPEC.md §1/§2 and PLANNING-NOTES.md §11.4.
- [ ] 6.5 Fix only findings from 6.4.

---

## Phase 7 — Application CRUD + ShellCheck validation

- [ ] 7.1
```
Implement only Sys.Admin's create-application endpoint: accepts the fields
from the applications table (Phase 2.2), encrypts credentials via Phase 3's
utility before storing. No script validation yet.
```
- [ ] 7.2
```
Implement only the ShellCheck validation step on Save per SPEC.md
§12.3: shellcheck --format=json on each script, error-level findings
return SHELLCHECK_FAILED (400, §4.2) with inline line+message,
warning/info/style are non-blocking suggestions.
```
- [ ] 7.3
```
Implement the ShellCheck-binary-unavailable case exactly per SPEC.md
§12.3 (v1.1): Save proceeds anyway (fail-open), UI shows a loud
persistent warning, and an audit entry is written with
action_type=SHELLCHECK_UNAVAILABLE, result=SUCCESS, detail noting
which script(s) went unvalidated. Stub the audit call here, wire it
for real in Phase 9.
```
- [ ] 7.4 Delegate to `shellcheck-triage` to confirm 7.2's severity
      gating matches the skill exactly.
- [ ] 7.5 Fix only findings from 7.4.
- [ ] 7.6
```
Implement only PUT /applications/{id} and DELETE /applications/{id}
(SPEC.md §4.3): PUT returns 403 if status != STOPPED (§6.4), DELETE
returns 409 APPLICATION_ALREADY_RUNNING if status != STOPPED (§4.2,
§6.4). **DELETE hard-deletes the row** (SPEC.md §3.1/§3.3, v1.1) — do
not set a `deleted_at` column, applications have no such column.
```
- [ ] 7.7
```
Confirm (do not implement anything extra): with applications now
hard-deleted, the existing `ON DELETE CASCADE` foreign keys on
`user_application_assignments.application_id` and
`application_log_lines.application_id` (SPEC.md §3.1) fire
automatically at the database level when DELETE /applications/{id}
removes the row. No application-layer cleanup code is needed for
either table — write a test proving both are empty for that
application_id after delete, rather than adding manual delete calls.
```
- [ ] 7.8 Delegate to `spec-gap-auditor` to confirm 7.6/7.7 match
      SPEC.md §3.1/§3.3/§6.4 exactly (v1.1, hard-delete), and that
      `audit_logs` rows referencing the deleted application are left
      completely untouched (per §3.1's "no FK, never reused" design).
- [ ] 7.9 Fix only findings from 7.8.

---

## Phase 8 — SSH integration layer 🔒

(Auth method per SPEC.md §3.1: username/password + host-key
fingerprint pinning.)

- [ ] 8.1 🔒
```
Implement only the function that opens an SSH connection to a single
application host using its decrypted stored username/password
credentials (SPEC.md §3.1). No script execution, no retries, no
timeout yet — just open and return a connected session or a typed
failure.
```
- [ ] 8.2 🔒
```
Implement only host-key fingerprint verification on top of 8.1: reject
the connection (SSH_AUTH_FAILED, SPEC.md §4.2) if the server's
presented host key doesn't match the stored ssh_host_key_fingerprint.
This is a new SPEC.md addition not in the original planning notes —
implement it as its own explicit check, don't fold it silently into
8.1.
```
- [ ] 8.3 🔒 Security review: confirm credentials are never logged in
      any log line or exception message, connection failures are
      typed/explicit (not swallowed), and 8.2's fingerprint check
      can't be bypassed or silently skipped on a mismatch.
- [ ] 8.4 Fix only findings from 8.3.
- [ ] 8.5 🔒
```
Implement only the function that takes an open, fingerprint-verified
session and runs the application's stored start/stop/log script over
it, capturing stdout, stderr, exit code. Per §3, this is intentionally
full arbitrary shell authored by Sys.Admin — the trust boundary is
"only Sys.Admin can reach this input surface," not the script content
itself. No timeout yet.
```
- [ ] 8.6 🔒 Security review focused on the *actual* threat model per
      §3: confirm no role other than Sys.Admin can create or edit an
      application's script content, and confirm nothing downstream
      re-interprets the script text as anything other than "run
      exactly this, as authored."
- [ ] 8.7 Fix only findings from 8.6.
- [ ] 8.8
```
Add timeout handling on top of 8.5 using SPEC.md §13.2's defaults
(connect 10s, start/stop 120s, log 15s): host unreachable, script
hangs, connection drops mid-run. Return a typed result distinguishing
these failure modes, mapping to ERROR status (§6.1) where appropriate.
```
- [ ] 8.9 Security review of timeout/failure logic only.
- [ ] 8.10 Fix only findings from 8.9.
- [ ] 8.11
```
Implement Idempotency-Key handling per SPEC.md §4.1 (24h TTL, per
user+application+key) on top of the `idempotency_keys` table from
Phase 2.7. No start/stop logic here — just accept the header,
check/store the key, return IDEMPOTENCY_CONFLICT (409) on a duplicate,
and opportunistically delete expired rows (`WHERE expires_at < NOW()`)
per §3.1.
```
- [ ] 8.12
```
Wire the start/stop application API endpoints (SPEC.md §4.3,
§6.2/§6.3) to call 8.1/8.2/8.5/8.8/8.11 in sequence, updating
application status through RUNNING/STOPPED/STARTING/STOPPING/ERROR
exactly per §6.1's state diagram. Do not add any new SSH logic here —
only call what already exists.
```
- [ ] 8.13 🔒 Full end-to-end security review of the wired-up
      endpoint. Verdict: PASS or FAIL.

---

## Phase 9 — Audit log

- [ ] 9.1
```
Implement only the insert-only audit-write function for LOGIN first,
using exactly the locked action_type enum from SPEC.md §3.2. Confirm
no secrets can ever land in the detail JSONB field.
```
- [ ] 9.2
```
Extend the write function to cover every remaining locked action type
from §3.2: LOGOUT, START_APPLICATION, STOP_APPLICATION,
CREATE_APPLICATION, UPDATE_APPLICATION, DELETE_APPLICATION,
ASSIGN_APPLICATION, RESET_PASSWORD, CHANGE_PASSWORD, CREATE_USER,
DELETE_USER, CREATE_ADMIN — each with result SUCCESS/FAILURE per §3.1.
```
- [ ] 9.3
```
Wire the ShellCheck fail-open audit call stubbed in Phase 7.3, using
the SHELLCHECK_UNAVAILABLE action_type added to §3.2.
```
- [ ] 9.4
```
Implement only GET /audit-logs (SPEC.md §4.3): paginated,
from/to-date-filtered, Admin+ only.
```
- [ ] 9.5 Delegate to `spec-gap-auditor` for full coverage review
      against §3.2's complete locked enum — every listed action type
      must actually be written somewhere in the codebase.
- [ ] 9.6 Fix only findings from 9.5.

---

## Phase 10 — Log polling pipeline (SPEC.md §7)

- [ ] 10.1
```
Implement only the function that runs one application's log_script
over the SSH layer (Phase 8, 15s timeout per §13.2) and returns the
raw ordered lines it printed, each expected to be prefixed with an
epoch-ms timestamp per §7.2's format. No dedup, no trimming, no
scheduling yet.
```
- [ ] 10.2
```
Implement only the deduplication algorithm from §7.2 exactly as
specified: recall the last-saved line's content, search the new batch
backward for the most recent matching occurrence, treat everything
after that match as new. Implement the fallback: if no match found
anywhere, treat the entire batch as new (§7.2 point 6).
```
- [ ] 10.3
```
Implement only the 500-line trim per application on top of 10.2, using
our own monotonic line_number (§3.1's unique index on
application_id+line_number), not anything derived from the script
output itself.
```
- [ ] 10.4
```
Implement only the scheduler per §7.1: one poller per application,
only while status = RUNNING, using that application's own
poll_interval_seconds, immediate poll on transition to RUNNING, and
cancel immediately on transition to STOPPED/ERROR.
```
- [ ] 10.5
```
Implement only the WebSocket broadcast of newly-appended lines to
/topic/application-logs/{applicationId} (§5.2, §7.2 point 7).
```
- [ ] 10.6
```
Implement only the SSH failure handling from §7.4: on connection/auth
failure, write "[ERROR] Log poll failed: <reason>" to the application
log stream, invalidate the cached SSH connection for lazy reconnect
next tick, no silent retries.
```
- [ ] 10.7 Delegate to `spec-gap-auditor` to confirm the dedup logic
      matches §7.2's algorithm exactly, including the backward-search
      and fallback rules — this is easy to get subtly wrong.
- [ ] 10.8 Fix only findings from 10.7.

---

## Phase 11 — Frontend: dashboard & application views

Stack per SPEC.md §9.1: React 18 + TypeScript + Vite, React Router v6
(lazy routes by role), TanStack Query v5, React Hook Form + Zod,
Tailwind + Headless UI. Scaffold each library's usage as its own small
step if this is new territory, rather than pulling all of them in at
once for the first component.

- [ ] 11.1
```
Implement only the role-gated route shell per SPEC.md §9.2: /
(Dashboard, all), /applications (Sys.Admin), /users (Admin+), /login,
/change-password (all, forced). No page content yet, just the gating.
```
- [ ] 11.2
```
Implement only the application grid view per §9.3: cards with name,
status badge, start/stop buttons, Time Started, Running Time. Scoped
by role per §2: User sees only assigned applications, Admin/Sys.Admin
see all. No start/stop wiring yet.
```
- [ ] 11.3
```
Implement only the empty-state variants from §9.4: User with 0
assignments gets "No applications assigned. Contact your admin."
Sys.Admin with 0 applications gets a message + "Add Application" CTA.
```
- [ ] 11.4
```
Implement only the access-denial behavior from §9.5: direct navigation
to an unauthorized route shows a 403 page, never a silent redirect.
```
- [ ] 11.5
```
Implement only the RUNNING/STARTING/STOPPING record-locking UI on the
grid cards from 11.2 (disabled/spinner during transitional states per
§6.1).
```
- [ ] 11.6
```
Wire the start/stop buttons to the Phase 8.12 endpoints via TanStack
Query, including the Idempotency-Key header (§4.1) on each request.
Do not add new state logic — only call what exists and reflect the
response.
```
- [ ] 11.7 Delegate to `rbac-anti-pattern-reviewer` for full review of
      11.1–11.6 against §2/§6.1/§9 rules.
- [ ] 11.8 Fix only findings from 11.7.

---

## Phase 12 — Frontend: log viewer

- [ ] 12.1
```
Implement only the embedded logs box per §9.3 with the log-source
dropdown: per-assigned-application logs for User, all applications +
Audit Log option for Admin/Sys.Admin.
```
- [ ] 12.2
```
Implement only the live-updating log content using @stomp/stompjs and
react-window (virtualized, §9.1) subscribing to the WebSocket topic
(Phase 10.5) for whatever is currently selected. Confirm switching
selection unsubscribes the old topic and subscribes the new one
(§5.3), resetting to bottom with auto-follow re-enabled.
```
- [ ] 12.3
```
Implement only the auto-follow / manual-scroll behavior from §9.3:
auto-follow bottom by default, manual scroll up pauses it and shows
"↓ Jump to latest."
```
- [ ] 12.4
```
Wire the "Audit Log" dropdown option (Admin/Sys.Admin only) to the
Phase 9.4 read endpoint, paginated, and to the /topic/audit-log
WebSocket topic (§5.2, Admin+ only).
```
- [ ] 12.5
```
Implement the WebSocket reconnect behavior from §5.3: exponential
backoff 1/2/4/8/16/30s, 10 attempts, then show a banner.
```
- [ ] 12.6 Delegate to `rbac-anti-pattern-reviewer`/`spec-gap-auditor`
      for a review of scoping + switch-cleanup + reconnect behavior.
- [ ] 12.7 Fix only findings from 12.6.

---

## Phase 13 — Sys.Admin-only management UI

- [ ] 13.1
```
Implement only the create/delete-user UI + endpoint wiring (Admin and
Sys.Admin both allowed per §1), enforcing no-self-deletion (Phase 6.2).
```
- [ ] 13.2
```
Implement only the create-Admin flow, Sys.Admin only (§1 — Admin
cannot create other Admins).
```
- [ ] 13.3
```
Implement only the add/edit/delete-application UI, Sys.Admin only, wired to
Phase 7's endpoints including the ShellCheck inline error/warning
display.
```
- [ ] 13.4
```
Implement only the "Assigned Applications" multi-select UI on the user-edit
screen (PLANNING-NOTES.md §11.4 naming exactly), wired to Phase 6.3's endpoint.
```
- [ ] 13.5 Delegate to `rbac-anti-pattern-reviewer` for a full review
      confirming Admin cannot reach 13.2/13.3 at all.
- [ ] 13.6 Fix only findings from 13.5.

---

## Phase 14 — ~~Staging-IP guard~~ Resolved: not needed

Earlier drafts of this doc treated 180.210.129.233 as a "never touch"
boundary requiring a code-level block. That was based on a
misunderstanding — it's actually the real deployment target where an
application is already running, and it should be managed by BPL like
any other application (start/stop over SSH, same as all the rest).

No IP-specific guard, blocklist, or hardcoded exception belongs
anywhere in the code. This server gets exactly the same protection
every application gets: `ssh_host_key_fingerprint` pinning (Phase
8.2 — connection refused if the host key ever changes unexpectedly)
and normal Sys.Admin-only RBAC on create/edit. Nothing else to build
here — this phase is a no-op, kept only so the history is visible.

---

## Phase 14b — Infra & hardening (SPEC.md §8, §11, new — not in original sequence)

These sections exist in SPEC.md but weren't covered by the original
PLANNING-NOTES.md-based sequence. Build them as their own small steps,
ideally before the Sept 2 demo since several are visible in a live run
(CSP headers, rate limiting).

- [ ] 14b.1
```
Configure CORS exactly per SPEC.md §8.4: dev origin
http://localhost:5173, prod single origin from env, credentials=true,
no wildcard with credentials.
```
- [ ] 14b.2
```
Add the CSP header exactly as specified in SPEC.md §8.5. Nothing else.
```
- [ ] 14b.3
```
Add login rate limiting (10 req/s per IP) and general API rate
limiting (Bucket4j, per-user + per-IP) per SPEC.md §8.6. Nothing else.
```
- [ ] 14b.4
```
Add the WebSocket connection limits from SPEC.md §5.4/§13.3 (per-user
5, per-application 50, global 500) and the reconnect backoff (§5.3:
1/2/4/8/16/30s, 10 attempts, then show a banner).
```
- [ ] 14b.5
```
Write the production Docker Compose file exactly per SPEC.md §11.1
(postgres, backend, frontend, caddy) and the application-prod.yml
config from §13.1. No changes to application logic here.
```
- [ ] 14b.6 Delegate to `spec-gap-auditor` for a final review of
      14b.1–14b.5 against §5/§8/§11/§13 together.
- [ ] 14b.7 Fix only findings from 14b.6.

---

## Phase 15 — QA end-to-end

- [ ] 15.1 Confirm `.claude/agents/qa-reviewer.md` was created in
      Phase 0a.12. If this doc is being run out of order and it wasn't,
      go back and do that first.
- [ ] 15.2
```
Delegate to qa-reviewer for a full end-to-end pass across all three
roles: login, dashboard, start/stop permitted vs. denied applications,
log viewing, audit log (Admin/Sys.Admin), user/application management
(Sys.Admin only vs Admin).
```
- [ ] 15.3
```
Delegate to qa-reviewer to check off every item in SPEC.md §15
(Acceptance Criteria) individually — including the ones easy to skip
in a manual pass: double-click Start → single execution (idempotency),
edit application while RUNNING → 403, delete while RUNNING → 409,
self-delete → 403, WebSocket reconnects after backend restart, all
timestamps UTC in DB / local in UI, CSP headers present with no
console errors, distroless backend image < 150MB.
```
- [ ] 15.4 Fix each failure from 15.2/15.3 as its own small step, not
      batched.
- [ ] 15.5 Re-run 15.2 and 15.3 until everything shows PASS.

---

## Phase 16 — Demo prep (Sept 2)

- [ ] 16.1 Decide the one intentional "gap caught live" demo moment
      (reuse something a reviewer agent actually caught earlier in
      this sequence — more convincing than staging a fake one).
- [ ] 16.2 Dry-run the full demo path once, end to end, timed.
- [ ] 16.3 Cross-check the presenter guide against the current
      PLANNING-NOTES.md/SPEC.md (repeat of the earlier "four lessons"
      versioning lesson — don't let the deck drift from source of truth).
- [ ] 16.4 Freeze the demo branch once 16.2/16.3 pass.

---

## Phase 17 — Final sign-off

- [ ] 17.1 Delegate to `spec-gap-auditor` for one final full-project pass.
- [ ] 17.2 Delegate to `qa-reviewer` for one final full-project pass.
- [ ] 17.3 Update TASKS.md/TASKS-decomposed.md marking everything done,
      recording any deviations in the notes log below.
- [ ] 17.4 V1 complete.

---

## Notes / decisions log

- Phase 0.1: ✅ resolved as a side effect of 0.7 — applications hard-delete now, cascade fires correctly.
- Phase 0.2 ShellCheck fail-open (runtime/prod) decision: ✅ **fail-open
  everywhere**, applied to SPEC.md v1.1 §3.2/§12.3.
- Phase 0.3 Idempotency-Key storage decision: ✅ **new DB table**,
  applied to SPEC.md v1.1 §3.1 (`idempotency_keys`).
- Phase 14 staging-IP: ✅ **resolved — not a guard at all.**
  180.210.129.233 is the real deployment target with an application
  already running there; it's managed like any other application (SSH
  host-key fingerprint pinning + RBAC), no IP-specific code anywhere.
- Phase 0.6 production-hardening scope (§8/§11): ✅ **build as scoped** — confirmed for real deployment, not just demo. Sequencing note: don't let Phase 14b block the Sept 2 demo date (see Phase 0's sequencing note).
- Phase 0.7 hard-delete vs. soft-delete for applications: ✅ **hard delete**, matching PLANNING-NOTES.md §10.4. `users.username` unique constraint fixed to a partial index (`WHERE deleted_at IS NULL`) since `users` remains soft-deleted.
- Phase 0.8 new audit action_type enum values: ✅ **`UPDATE_APPLICATION`** and **`CHANGE_PASSWORD`** added to SPEC.md §3.2 (v1.1). New endpoint `POST /auth/change-password` added to support the latter.
- Phase 0.9 SPEC.md section-numbering fix: ✅ **fixed** — §11/§12 sub-headings corrected in v1.1; every citation in this document cross-checked and corrected (SSH timeouts §13.2, WebSocket limits §5.4/§13.3, Docker Compose §11.1, prod config §13.1, ShellCheck §12.3, bootstrap/password-reset §12.1/§12.2).
- Phase 0.10 host-key fingerprint acquisition workflow: ✅ **trust-on-first-connect**, described in SPEC.md §12.3 (v1.1) — Test Connection step, explicit confirmation, re-verification on save.
- Phase 0.11 password hashing: ✅ **bcrypt**, corrected in SPEC.md §8.1/§3.1 (v1.1) — it's the actual Spring Security default, Argon2id was not.

