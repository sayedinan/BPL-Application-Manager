# Claude Code Setup Guide — Skills, Hooks, Agents

> Built incrementally, piece by piece, as we design each one together.
> This is a companion to PLANNING-NOTES.md — that file is *what* we're
> building; this file is *how the `.claude/` folder is set up* to help
> Claude Code build it well, and to visibly demonstrate the
> skills/hooks/sub-agents/plan-mode requirement from the TL's brief.

---

## What these three things actually are (quick reference)

| Folder | What it is | Analogy |
|---|---|---|
| `.claude/skills/` | Reference documents Claude Code reads before working on a specific area — the *pattern to follow*, not code itself | Like an internal wiki page: "here's how we do X on this project" |
| `.claude/hooks/` | Scripts that run automatically around tool use (before a file write, before a bash command) — can actually **block** an action | Like a linter or a pre-commit check, but for the AI's actions |
| `.claude/agents/` | Specialized sub-agent personas Claude Code can delegate specific work to | Like assigning a task to "the backend specialist" vs. "the reviewer" on a team |

---

## Hooks

### Hook 1: Block references to the live staging IP during development

**Why this exists:** The real BPL application server (`180.210.129.233`) is
shared with your JMeter integration test suite. During *development* of
this project, nothing Claude Code writes or runs should reference that
address — test/seed data should point at a placeholder or local address
instead. This is **not** meant to block the finished product from ever
using that IP — once you're done building and are ready to actually
deploy against the real server, that's the intended final use, and adding
that application is something *you* do deliberately through the app's UI (with
its own confirmation step — see the note below), not something an agent
does automatically while coding.

**Type:** `PreToolUse` hook, blanket block (any file write, any bash
command, no exceptions/scoping — simplest possible rule).

**What it does:** checks whether the exact string `180.210.129.233`
appears anywhere in the file content being written/edited, or in the
shell command about to run. If it does, it blocks the action and prints
an explanation. If not, it does nothing and lets the action through
normally.

**The script** (`.claude/hooks/block-staging-ip.sh`):

```bash
#!/bin/bash
# block-staging-ip.sh
# Blocks any Bash command or file write that references the live
# staging server IP (180.210.129.233), shared with the JMeter suite.
# This protects the BUILD process only — see the note above about the
# finished product's intended use of this address.
#
# Exit code 2 = block. Exit code 0 = allow.

set -euo pipefail

STAGING_IP="180.210.129.233"

INPUT=$(cat)

# Two tool shapes: Bash has tool_input.command, Write/Edit have
# tool_input.content (Write also has tool_input.file_path).
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
CONTENT=$(echo "$INPUT" | jq -r '.tool_input.content // empty')
PAYLOAD="${COMMAND}${CONTENT}"

if [ -z "$PAYLOAD" ]; then
  exit 0
fi

if echo "$PAYLOAD" | grep -qF "$STAGING_IP"; then
  echo "Blocked: this references the live staging server ($STAGING_IP)," >&2
  echo "shared with the JMeter integration suite." >&2
  echo "" >&2
  echo "During development, test/seed data should use a placeholder or" >&2
  echo "local address instead. If you are intentionally configuring the" >&2
  echo "real production application, do this manually through the app's UI" >&2
  echo "once the product is finished — not through an automated edit." >&2
  exit 2
fi

exit 0
```

**How to set it up (step by step):**

1. In your project folder, create the file at exactly this path:
   `.claude/hooks/block-staging-ip.sh`
2. Paste in the script above.
3. Make it executable (Git Bash / terminal):
   ```
   chmod +x .claude/hooks/block-staging-ip.sh
   ```
4. Wire it up in `.claude/settings.json` so Claude Code actually runs it
   before every file write/edit and every bash command. Add this to the
   `hooks` section (create the section if it doesn't exist yet):
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "Bash",
           "hooks": [
             { "type": "command", "command": "bash \"$CLAUDE_PROJECT_DIR\"/.claude/hooks/block-staging-ip.sh" }
           ]
         },
         {
           "matcher": "Write|Edit",
           "hooks": [
             { "type": "command", "command": "bash \"$CLAUDE_PROJECT_DIR\"/.claude/hooks/block-staging-ip.sh" }
           ]
         }
       ]
     }
   }
   ```
5. That's it — no restart needed most of the time, but if Claude Code
   was already running when you added this, start a fresh session so it
   picks up the new hook.

**How to verify it works:** ask Claude Code to write a test file
containing the string `180.210.129.233` somewhere in it. It should
refuse, showing the blocked message above. Then ask it to write the same
file with a placeholder IP instead (e.g. `10.0.0.99`) — it should succeed
normally. This is also a good moment to screenshot for your Wednesday
demo, since it's a clean, visible before/after.

---

## Skills

### Skill 1: `application-script-validation`

**Why this skill exists:** Sys.Admin writes real shell scripts (start,
stop, log) for every application, executed via SSH on that application's server
(PLANNING-NOTES.md §3). These scripts need to be checked for correctness
before saving — but the exact rules for *how strict* that check is, and
*what happens if the checking tool itself isn't available*, are easy to
get subtly wrong if re-derived from scratch each time this code is
touched. This skill pins the rules down once.

**File:** `.claude/skills/application-script-validation/SKILL.md`

**What goes in it:**

```markdown
---
name: application-script-validation
description: Rules for validating Sys.Admin-authored start/stop/log
  scripts using ShellCheck before an application is saved. Covers severity
  gating and what happens if ShellCheck itself is unavailable.
---

# Application script validation

Every application's start/stop/log script is validated with ShellCheck at
save time (create or edit), run **locally on the backend** — never
against the remote application server. This is pure static analysis; no SSH
connection is needed to validate a script's syntax/correctness.

## Staging server confirmation (separate from ShellCheck, same save flow)

If the `serverIp` field on the Application form matches the known live
staging address (`180.210.129.233`, shared with the JMeter integration
suite), do NOT block saving — but DO show an explicit confirmation
dialog before the save completes (e.g. "This is the shared JMeter
staging server — confirm you intend to point a managed application at it").
This is a deliberate-action safeguard, not a hard block: pointing an
application at this address is the legitimate final use of the finished
product, so it must remain possible, just never accidental. See
PLANNING-NOTES.md §12.2 for the full reasoning.

## Invocation

Run: `shellcheck --format=json <script-file>`

Parse the JSON output into a list of findings, each with a `level`
(error / warning / info / style), a line number, and a message.

## Severity gating (this is the part most likely to be gotten wrong)

- **`error` level findings BLOCK saving.** Show each one inline, with
  its line number and message, next to the relevant script field. The
  user cannot save until these are fixed.
- **`warning` / `info` / `style` findings do NOT block saving.** Show
  them as non-blocking suggestions (e.g. a collapsible "3 suggestions"
  section), but allow Save to proceed.
- Do not treat all ShellCheck findings as equally blocking. ShellCheck's
  stricter checks are often stylistic opinions, not real bugs — blocking
  on all of them would frustrate a developer trying to ship a working
  script for no real safety benefit.

## If ShellCheck itself can't run (fail open — NOT fail closed)

This is a different situation from the script having errors. If the
`shellcheck` binary is missing, or the process call itself fails for an
environment reason (permission denied, binary not found, crash) — this
means the checking tool is unavailable, not that the script is bad.

**In this case: allow Save to proceed anyway.** Do not block application
creation/editing just because the validation tool had a problem. But:

- Show a clear, visible warning in the UI at save time, e.g.:
  `⚠ Script validation unavailable — saved without checking.`
- Write an audit log entry noting that this particular save happened
  without validation.

This is a deliberate choice, not an oversight: ShellCheck being
unavailable is a one-time setup problem that shouldn't be able to fully
block using the app while it gets fixed. It is also not the only safety
net — a genuinely broken script will still surface later as a
`SSH_COMMAND_FAILED` error when the application is actually started.

## Anti-patterns to avoid

- Do NOT validate the script against the remote application server via SSH —
  this check is local static analysis only.
- Do NOT block Save on warning/info/style findings.
- Do NOT block Save if ShellCheck itself fails to run — fail open with a
  visible warning instead (see above).
- Do NOT silently skip validation without telling the user — the warning
  must be visible, not just logged server-side.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test that an `error`-severity finding blocks save, a test that a
  `warning`-only finding does NOT block save, a test that a simulated
  ShellCheck-unavailable condition still allows save (with the warning
  shown/logged).
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:**

1. Create the folder `.claude/skills/application-script-validation/`
2. Create `SKILL.md` inside it with the content above
3. No wiring needed in `settings.json` for skills (unlike hooks) — Claude
   Code discovers skills automatically by reading the `.claude/skills/`
   folder and matching the `description` field to what it's currently
   working on. When you ask it to build or modify anything related to
   application script validation, it should pick this file up on its own.

**How to verify it works:** ask Claude Code something like "implement
the script validation logic for application creation" without re-explaining
the severity rules yourself, and check whether its implementation
matches what's written above (error blocks, warning doesn't, fail-open
on tool unavailability). If it does, the skill is being picked up
correctly.

---

### Skill 2: `ssh-connection-handling`

**Why this skill exists:** every application needs SSH calls for start, stop,
and log-reading (PLANNING-NOTES.md §10.2). The connection strategy
(reuse vs. fresh-per-call, when to invalidate, how to clean up) has real
performance and correctness consequences if re-derived inconsistently
across different parts of the codebase that touch SSH.

**File:** `.claude/skills/ssh-connection-handling/SKILL.md`

**What goes in it:**

```markdown
---
name: ssh-connection-handling
description: How SSH connections to application servers are opened, reused,
  and cleaned up. Covers the reuse-with-lazy-reconnect strategy,
  timeouts, and failure handling.
---

# SSH connection handling

## Core rule: reuse, don't reconnect every call

Keep **one cached SSH connection per application**, keyed by application ID. Do
NOT open a fresh connection for every start/stop/log-read call — at a
short poll interval (e.g. every 5 seconds per application, configurable), the
overhead of a new TCP handshake + SSH negotiation + auth every single
call is real and avoidable, and it compounds as more applications are added.

## When to reuse vs. reconnect

Before each use (a poll tick, a start call, a stop call):
1. Check if the cached connection for this application is still alive.
2. If alive, use it directly.
3. If dead or missing, open a fresh connection and cache it.

## Failure handling — the important distinction

There are two very different kinds of failure, and they must be
handled differently:

- **Connection-level failure** (broken pipe, timeout, connection
  refused, auth rejected at the transport level) — this means the
  cached connection itself is bad. **Invalidate the cache** so the next
  attempt opens a fresh connection instead of repeatedly failing against
  a dead handle.
- **Script-level failure** (the command ran, but exited non-zero) — the
  connection is perfectly fine; only the script failed. This is a normal
  `SSH_COMMAND_FAILED` result. **Do NOT invalidate the connection cache
  for this** — the connection worked, it just carried back a script
  error.

Conflating these two is the most likely mistake here: treating an
ordinary non-zero script exit as a reason to throw away a perfectly
good cached connection would cause unnecessary reconnect overhead on
every routine script failure.

## Cleanup

On application STOP or application deletion: explicitly close and discard that
application's cached connection. No orphaned connections should linger after
an application stops being active (application deletion already requires STOPPED
first — see PLANNING-NOTES.md §9.1).

## Timeouts (configurable, not hardcoded — these are sensible defaults)

- Connect timeout: 5 seconds
- Command timeout for start/stop scripts: 30 seconds (these can
  reasonably take a while)
- Command timeout for log-read commands: ~10 seconds (should be fast; a
  hang here shouldn't block the log poller indefinitely)

## Anti-patterns to avoid

- Do NOT open a new connection for every single call — reuse per application.
- Do NOT invalidate the cached connection just because a script exited
  non-zero — only invalidate on genuine connection-level failures.
- Do NOT leave a connection open after an application is stopped or deleted.
- Do NOT hardcode timeout values inline scattered across the codebase —
  keep them as named, configurable constants in one place.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test that two calls within the timeout window reuse one connection, a
  test that a connection-level failure invalidates the cache, and a test
  that a script-level (non-zero exit) failure does NOT invalidate the
  cache.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** same pattern as Skill 1 — create the folder
`.claude/skills/ssh-connection-handling/`, put `SKILL.md` inside with
the content above. No `settings.json` wiring needed.

**How to verify it works:** ask Claude Code to implement or explain the
SSH connection logic, and check that it distinguishes connection-level
failures from script-level failures correctly, and that it's reusing
connections rather than opening one per call.

---

### Skill 3: `log-poller-dedup`

**Why this skill exists:** the background log poller (PLANNING-NOTES.md
§10.3) has to figure out which lines from a freshly-run log script are
genuinely new, since Sys.Admin-authored scripts realistically return a
snapshot window (like `tail -n 500`) rather than an incremental feed.
Getting this diffing logic subtly wrong causes either duplicate lines or
lost lines — worth pinning down precisely once.

**File:** `.claude/skills/log-poller-dedup/SKILL.md`

**What goes in it:**

```markdown
---
name: log-poller-dedup
description: How the background log poller determines which lines from
  a freshly-run log script are new, avoiding duplicates without
  requiring the script itself to support incremental output.
---

# Log poller deduplication

## The two-layer delivery model

Don't conflate these two different mechanisms:

1. **Initial load** (opening the Logs box, or switching applications in the
   dropdown): a plain REST call returns whatever's currently stored in
   the database for that application — up to the last 500 lines. This is
   served once, instantly, as history.
2. **Live updates from then on**: delivered over WebSocket, and only
   contain the **new** lines discovered on each poll tick — small,
   frequent deltas appended to what's already shown. Never re-send the
   full 500 lines over the WebSocket; only send what's new.

## The per-tick diffing algorithm

The log script cannot be assumed to support "give me only new lines
since last time" — it realistically returns a snapshot window (e.g.
`tail -n 500 file.log`) every time it runs. The poller's job is to
figure out what's new from that snapshot:

1. Run the log script over SSH → get an ordered list of lines (oldest
   to newest), whatever window the script returns.
2. Recall the **content of the last line** this poller previously saved
   for this application.
3. Search the new batch **from the end, backward**, for that same line
   content.
   - Search backward, not forward: if that exact text repeats multiple
     times in the batch (e.g. a generic "heartbeat" message appearing
     often), matching the **most recent** occurrence minimizes the risk
     of re-treating already-seen content as new.
4. Everything **after** the matched position is genuinely new. Append
   those lines to the database with an internally-assigned sequential
   line number (do not derive the line number from the script's
   output), trim the table back to 500 rows for that application, and
   broadcast only the new lines over WebSocket.
5. **Fallback:** if the last-known line's content is not found anywhere
   in the new batch (this happens if the log file was rotated/
   truncated, or enough volume occurred between polls that the old tail
   scrolled entirely out of the window), treat the **entire new batch as
   new**. Prefer risking a duplicate line over silently losing content.

## No special-casing needed for "application just started"

If an application's log was freshly cleared when it started, the "last known
line" from before naturally won't be found in the new batch, and the
fallback in step 5 handles it correctly using the exact same logic as
any other poll tick. Do not write separate logic for "first poll after
start."

## This is heuristic, not a guarantee — and that's accepted

If a script's output contains many genuinely identical lines close
together, this algorithm can occasionally misjudge the new/old boundary
by a line or two. This is a known, accepted trade-off for an internal
admin tool — do not attempt to build a more complex/perfect solution
than what's described here; it isn't worth the added complexity.

## Anti-patterns to avoid

- Do NOT require or assume the log script returns only new lines —
  design for a snapshot-window script.
- Do NOT re-send the full 500-line history over WebSocket on every
  tick — only send genuinely new lines.
- Do NOT search forward through the batch when looking for the last
  known line — search backward, for the reason in step 3 above.
- Do NOT treat "last known line not found" as an error condition — it's
  an expected case (log rotation, etc.) with a defined fallback.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test with repeated identical lines confirming the backward-search
  picks the most recent match, a test simulating log rotation/truncation
  confirming the fallback (treat entire batch as new) fires correctly,
  and a test confirming only new lines (not the full 500) are broadcast
  over WebSocket.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/log-poller-dedup/`, add
`SKILL.md` with the content above.

**How to verify it works:** ask Claude Code to implement or explain the
log poller's new-line detection, and check it includes the backward
search, the fallback case, and correctly separates the REST-snapshot vs.
WebSocket-delta delivery model.

---

### Skill 4: `audit-log-coverage`

**Why this skill exists:** the audit log's entire value depends on it
being complete (every state-changing action recorded) and permanent
(never edited, never deleted, never silently orphaned by unrelated
deletions elsewhere). This skill makes both guarantees explicit so
they're not accidentally weakened later.

**File:** `.claude/skills/audit-log-coverage/SKILL.md`

**What goes in it:**

```markdown
---
name: audit-log-coverage
description: Which actions must write an audit log entry, how audit
  entries are written (aspect-based, not manual calls), and the
  permanence rules that keep the audit log trustworthy.
---

# Audit log coverage

## Mechanism: aspect/annotation-based, not manual calls

State-changing methods are marked with an annotation (e.g.
`@Audited(action = START_APPLICATION)`). A single aspect wraps every
annotated method, extracts the current actor from the session, captures
whether the action succeeded or failed, and writes the audit row
automatically.

**Do NOT write audit-log calls manually inside individual service
methods.** The whole point of the aspect approach is that no
state-changing action can be accidentally left out of the audit trail
because a developer forgot to add a call — new annotated methods are
covered automatically.

## What counts as a state-changing action requiring the annotation

- Application: create, edit, delete, start, stop
- User: create, edit (role or assigned-applications change), delete
- Auth: login, logout, password change (including the forced
  first-login change from the mustChangePassword flow)

Read-only actions (viewing the dashboard, viewing logs, viewing the
audit log itself) do NOT get audited — only actions that change state.

## Permanence rules (do not weaken these)

- Audit rows are **insert-only**: never edited, never deleted, by
  anything, under any circumstances — including when the thing they
  reference (an application, a user) is later deleted.
- When an **application** is deleted: its audit log entries are left
  completely untouched. The application's numeric ID stored in the audit row
  is a **plain value, not an enforced foreign key** — because application IDs
  are never reused, it's always safe for this value to keep pointing at
  an ID that no longer exists in the applications table. Do not add an
  `ON DELETE CASCADE` or `ON DELETE SET NULL` constraint here; there
  should be no foreign-key constraint enforced on this column at all.
- Every audit row also stores a **plain-text snapshot of the relevant
  application/user name** at the time of the action (not solely an ID
  reference), so historical entries remain human-readable
  (e.g. "Started BPL Order Application") even after the referenced application or
  user no longer exists.

## Anti-patterns to avoid

- Do NOT write audit rows via scattered manual calls in service code —
  use the annotation + aspect mechanism.
- Do NOT add any code path that edits or deletes an existing audit row.
- Do NOT cascade-delete audit rows when an application or user is deleted.
- Do NOT rely solely on a foreign key / ID reference for readability —
  always also store the plain-text name snapshot.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test that every annotated state-changing endpoint produces exactly one
  audit row per call, a test that deleting an application leaves its existing
  audit rows completely intact (row count and content unchanged), and a
  test that no code path can edit or delete an existing audit row.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/audit-log-coverage/`, add
`SKILL.md` with the content above.

**How to verify it works:** ask Claude Code to add a new state-changing
endpoint (or review an existing one) and confirm it uses the annotation
pattern rather than a manual audit-write call, and that deleting an
application in a test doesn't remove or break its historical audit entries.

---

### Skill 5: `rbac-and-application-assignment`

**Why this skill exists:** this project deliberately uses two related
but distinct concepts — the RBAC tier and per-application access — and it's
easy for these to get blurred back together in code or conversation if
not stated explicitly every time (this already happened once during
planning; see PLANNING-NOTES.md §11.4).

**File:** `.claude/skills/rbac-and-application-assignment/SKILL.md`

**What goes in it:**

```markdown
---
name: rbac-and-application-assignment
description: The three-tier role model (Sys.Admin/Admin/User), the
  separate "assigned applications" concept, and the forced-password-change
  flow for new accounts. Clarifies terminology that is easy to conflate.
---

# RBAC tiers and application assignment

## Two different concepts — do not use the word "role" for both

- **"Role"** means ONLY the RBAC tier: `SYS_ADMIN`, `ADMIN`, or `USER`.
  One column on the `users` table: `role`.
- **"Assigned applications"** (never "role") is the separate concept of which
  specific applications a User can see/start/stop/view logs for. This is a
  many-to-many relationship, stored in a table named
  `user_application_assignments` (not `user_application_roles` — that name was
  considered and rejected specifically to avoid the naming collision).

If you are about to write or say "the user's role is BPL" — stop; the
correct phrasing is "the user is **assigned** the BPL application."
`SYS_ADMIN`/`ADMIN`/`USER` are the only valid values for the word
"role" anywhere in this codebase.

## The three-tier permission matrix

| Action | SYS_ADMIN | ADMIN | USER |
|---|---|---|---|
| Create/delete users | ✅ | ✅ (User role only) | ❌ |
| Create/delete Admins | ✅ | ❌ | ❌ |
| Add/delete/edit applications | ✅ | ❌ | ❌ |
| See all applications | ✅ | ✅ | ❌ (assigned only) |
| Start/stop applications | ✅ (all) | ✅ (all) | ✅ (assigned only) |
| See audit log | ✅ | ✅ | ❌ |
| See application logs | ✅ (all) | ✅ (all) | ✅ (assigned only) |
| Assign applications to users | ✅ | ✅ | ❌ |

Admin's access to applications/users is **global**, not scoped to any subset
— an earlier idea to scope Admin per-application was explicitly discarded
during planning.

## Forced password change on account creation

Every new account (including the one-time seeded first SYS_ADMIN) is
created with `mustChangePassword = true`. On login, if this flag is
true, the user must be routed to a change-password screen before
reaching anything else in the app — no skipping, no navigating away.
Once changed, the flag becomes false. If an admin later resets someone's
password, that action should set the flag back to true.

## Anti-patterns to avoid

- Do NOT use the word "role" to describe application access — use "assigned
  applications" / "application assignment."
- Do NOT name the join table anything containing the word "role" — it's
  `user_application_assignments`.
- Do NOT scope Admin's access to a subset of applications — Admin sees/
  controls all applications, same as Sys.Admin (only application CRUD and user-tier
  creation differ between them).
- Do NOT allow a user with `mustChangePassword = true` to reach any page
  other than the change-password screen.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test confirming a USER-role request to an Admin/Sys.Admin-only endpoint
  is rejected, a test confirming Admin's application visibility is NOT scoped
  to a subset, and a test confirming a user with `mustChangePassword =
  true` is redirected away from every page except the change-password
  screen.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/rbac-and-application-assignment/`,
add `SKILL.md` with the content above.

**How to verify it works:** ask Claude Code to describe or implement
anything involving user permissions, and check it consistently uses
"assigned applications" (never "role") for the per-application concept, and
correctly enforces the forced-password-change flow.

---

### Skill 6: `frontend-realtime-log-viewer`

**Why this skill exists:** the Logs box (PLANNING-NOTES.md §5) has real
interaction behavior that's easy to get wrong if re-derived on the fly —
specifically, a naive "always scroll to bottom on new data" implementation
is actively annoying to use, and switching applications in the dropdown needs
clean WebSocket subscription management to avoid leaking or crossing
subscriptions.

**File:** `.claude/skills/frontend-realtime-log-viewer/SKILL.md`

**What goes in it:**

```markdown
---
name: frontend-realtime-log-viewer
description: Behavior of the Dashboard's Logs box — auto-scroll/pause/
  jump-to-latest, and WebSocket subscribe/unsubscribe when the dropdown
  selection changes.
---

# Frontend realtime log viewer

## Initial load vs. live updates

- On opening the Logs box, or selecting a new entry in its dropdown:
  fetch the initial history via `GET /api/applications/{id}/logs` (a normal
  REST call) and render it immediately.
- After that initial render, subscribe to the corresponding WebSocket
  topic (`/topic/application-logs/{id}` or `/topic/audit-log`) for live
  updates. Only append new incoming lines to what's already rendered —
  never re-fetch or re-render the full history on each new line.

## Dropdown switching — subscription hygiene

When the user selects a different entry in the dropdown:
1. Unsubscribe from the previously-active WebSocket topic first.
2. Clear the currently-rendered lines.
3. Fetch the new selection's history via REST.
4. Subscribe to the new topic.

Do NOT stay subscribed to more than one topic at a time from a single
Logs box instance — this would cause lines from the wrong application (or the
audit log) to appear mixed into the wrong view.

## Auto-scroll behavior

- By default, the log viewer auto-follows the bottom (newest line) as
  new lines arrive.
- If the user manually scrolls up to read older lines, **auto-follow
  pauses** — new lines still arrive and are stored, but the view does
  not yank the user back down to the bottom while they're reading.
- While paused, show a "↓ Jump to latest" affordance (e.g. a small
  floating button). Clicking it scrolls to the bottom and re-enables
  auto-follow.
- Switching the dropdown selection always resets to the bottom and
  re-enables auto-follow, regardless of the previous scroll state.

## Anti-patterns to avoid

- Do NOT force-scroll to the bottom on every new line regardless of
  where the user has scrolled to — this is a well-known UX annoyance for
  live log viewers.
- Do NOT leave a WebSocket subscription active after switching away from
  it in the dropdown.
- Do NOT subscribe to a new topic before unsubscribing from the old one
  — always unsubscribe first to avoid a brief window of mixed data.
- Do NOT re-fetch the full history on every incoming WebSocket message —
  only fetch history once, on initial load / dropdown switch.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test that scrolling up pauses auto-follow while new lines still
  accumulate in state, a test that switching the dropdown unsubscribes
  the old topic before subscribing to the new one, and a test that no
  more than one topic is subscribed at a time.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/frontend-realtime-log-viewer/`,
add `SKILL.md` with the content above.

**How to verify it works:** open the Logs box, scroll up while new lines
are arriving, and confirm the view doesn't jump back down until you
click "Jump to latest." Then switch the dropdown and confirm the old
application's log lines stop arriving.

---

### Skill 7: `frontend-auth-session-handling`

**Why this skill exists:** session-based auth (PLANNING-NOTES.md §6.3)
needs consistent frontend handling of three things that are easy to
implement inconsistently across different pages if not stated once
clearly: hydrating auth state on page load, handling an expired/missing
session, and enforcing the forced-password-change redirect.

**File:** `.claude/skills/frontend-auth-session-handling/SKILL.md`

**What goes in it:**

```markdown
---
name: frontend-auth-session-handling
description: How the frontend hydrates auth state on load, handles
  401/expired sessions, and enforces the mustChangePassword redirect
  lock. Session-based auth via HttpOnly cookie, not JWT.
---

# Frontend auth & session handling

## Hydrating auth state on page load

On every app load (including a hard refresh), call `GET /api/auth/me`
before rendering any protected page. This confirms whether a valid
session cookie exists and returns the current user's `id`, `username`,
`role`, and `assignedApplicationIds`. Do NOT assume a user is logged in based
only on local/client-side state — the cookie-backed session on the
server is the source of truth.

## Handling a missing/expired session

If `GET /api/auth/me` returns 401 (no valid session), redirect to the
login page. This should happen automatically on any API call that
returns 401 while the user is on a protected page mid-session too — not
just on initial load — since a session can expire while the app is open.

## The mustChangePassword redirect lock

If the hydrated user has `mustChangePassword = true` (see
`rbac-and-application-assignment` skill for the full flow), the frontend must
redirect to the change-password screen and **prevent navigation to any
other page** until the flag is cleared. This applies immediately after
login and also on any page-load hydration while the flag is still true
(e.g. the user closed the tab mid-flow and came back later).

## Login flow

1. Submit credentials to the login endpoint.
2. On success, re-fetch (or use the login response's) user data.
3. If `mustChangePassword = true`, redirect to the change-password
   screen (see above). Otherwise, redirect to the Dashboard.

## Anti-patterns to avoid

- Do NOT store the session/auth state only in client-side memory or
  localStorage as the source of truth — always confirm against `GET
  /api/auth/me` on load, since the actual session lives server-side in
  the cookie.
- Do NOT allow any page other than the change-password screen to render
  while `mustChangePassword = true`.
- Do NOT silently fail on a 401 mid-session — always redirect to login.
- Do NOT implement any part of this as if it were JWT-based (no token
  refresh logic, no client-side token expiry checking — the server-side
  session is authoritative).

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test that a hard refresh with an expired session redirects to login, a
  test that `mustChangePassword = true` blocks navigation to the
  Dashboard even via direct URL, and a test that a 401 received mid-
  session (not just on load) triggers a redirect to login.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/frontend-auth-session-handling/`,
add `SKILL.md` with the content above.

**How to verify it works:** log in, then manually clear cookies in dev
tools and refresh — confirm it redirects to login rather than showing a
broken/stuck page.

---

### Skill 8: `dashboard-application-states`

**Why this skill exists:** the Dashboard needs to render several
distinct situations correctly (PLANNING-NOTES.md §5, §9.1) — empty
states differ by role, unauthorized direct navigation needs an explicit
denial page rather than a silent redirect, and the entire application record
must be locked from editing while RUNNING/STARTING/STOPPING. These are
easy to handle inconsistently if not stated together in one place.

**File:** `.claude/skills/dashboard-application-states/SKILL.md`

**What goes in it:**

```markdown
---
name: dashboard-application-states
description: Dashboard empty states by role, the access-denial page for
  unauthorized direct navigation, and locking application edit forms while
  the application is RUNNING/STARTING/STOPPING.
---

# Dashboard and application state handling

## Empty states (differ by role — do not use one generic message)

- **User with zero assigned applications:** show a plain informational
  message (e.g. "No applications assigned to you yet. Contact your admin for
  access.") — no call-to-action button. A User cannot self-assign
  applications, so a CTA here would be misleading.
- **Sys.Admin with zero applications configured:** show a message plus an
  "Add Application" call-to-action, since Sys.Admin is exactly the role that
  can act on this state immediately.

## Access denial (explicit page, not a silent redirect)

If a logged-in user directly navigates (via URL) to a route their role
cannot use — e.g. a USER hitting `/applications` or `/users` — render a clear
"you don't have access" page. Do NOT silently redirect back to the
Dashboard with no explanation. An explicit denial page is more honest
about what happened and easier to support/debug than a silent bounce
that leaves the user wondering why nothing happened.

## Locking the application record while RUNNING/STARTING/STOPPING

Per PLANNING-NOTES.md §9.1: the **entire** application edit form (name,
server IP, SSH credentials, all three scripts, poll interval — every
field, not just the "sensitive" ones) is locked/disabled whenever that
application's status is `RUNNING`, `STARTING`, or `STOPPING`. It only becomes
editable again once the application is `STOPPED` or `ERROR`. This is a
whole-record lock, not a partial one — do not allow editing "safe"
fields like the display name while the application is active.

The Dashboard card itself should also reflect the transitional states
visibly: show a distinct visual treatment (e.g. spinner, disabled
buttons) during `STARTING`/`STOPPING`, not just a plain status label.

## Anti-patterns to avoid

- Do NOT show the same empty-state message to both User and Sys.Admin —
  the CTA is role-specific.
- Do NOT silently redirect on unauthorized direct navigation — always
  show an explicit denial page.
- Do NOT allow partial editing of an application's record while it's active
  — the lock applies to the whole record, including seemingly harmless
  fields like the name.
- Do NOT render `STARTING`/`STOPPING` with the same static appearance as
  `RUNNING`/`STOPPED` — they need a visibly distinct in-progress state.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a
  test confirming the User empty state has no CTA while the Sys.Admin
  empty state does, a test confirming a USER navigating directly to
  `/applications` sees a denial page rather than being silently redirected,
  and a test confirming every field in the application edit form is disabled
  while status is RUNNING.
- If you hit an error, an ambiguous case, a failing test you can't
  explain, or a situation this skill doesn't clearly answer while working
  in this area: **stop coding.** Do not guess and keep going. Report the
  exact error or ambiguity, propose your best suggested fix or
  interpretation, and wait for confirmation before proceeding.
```

**How to set it up:** create `.claude/skills/dashboard-application-states/`,
add `SKILL.md` with the content above.

**How to verify it works:** as a User with no assigned applications, confirm
the Dashboard shows the no-CTA message; as a USER, try navigating
directly to `/applications` by URL and confirm you see a denial page, not a
silent bounce back to the Dashboard.

---

## Agents

Sub-agents are delegated to for a bounded task with its own context
window — they don't get invoked constantly like hooks, and they don't
just get *read* like skills; the main Claude Code session hands off
work and gets a result back. Each one below has a narrow job and a
restricted toolset on purpose.

### Agent 1: `rbac-anti-pattern-reviewer`

**Why this agent exists:** the anti-pattern lists in `rbac-and-application-
assignment`, `frontend-auth-session-handling`, and `dashboard-application-
states` are easy to check right after writing the code — and easy to
rubber-stamp, since the same session that wrote it is reviewing it. A
separate agent with no memory of *why* the code was written, only the
rules it must satisfy, gives an honest second look.

**File:** `.claude/agents/rbac-anti-pattern-reviewer.md`

**What goes in it:**

```markdown
---
name: rbac-anti-pattern-reviewer
description: Adversarial reviewer for auth/RBAC/dashboard-state code.
  Delegate to this agent after writing or editing anything touching
  session handling, role gating, or application-state locking, before
  considering that work done.
tools: Read, Grep, Glob
---

You are a strict, adversarial code reviewer. You did not write this
code and have no stake in it looking good — your only job is to find
violations of the rules below and report them. Do not fix anything.
Do not soften findings to be encouraging.

Check the changed files against these anti-pattern lists (full detail
in the referenced skills):
- `rbac-and-application-assignment`: role-gating logic matches the 3-tier
  model exactly (Sys.Admin / Admin / User), no application access checks
  skipped for convenience.
- `frontend-auth-session-handling`: no localStorage-as-source-of-truth,
  no JWT-shaped logic, mustChangePassword lock covers every route, 401
  triggers redirect both on load and mid-session.
- `dashboard-application-states`: empty states differ by role, unauthorized
  direct navigation shows a denial page (never a silent redirect),
  RUNNING/STARTING/STOPPING locks the whole application record.

Output a numbered list of violations found, each with file, line, the
specific rule broken, and why it matters. If you find none, say so
plainly — do not invent findings to seem thorough. End with a one-line
verdict: PASS or FAIL (any anti-pattern present = FAIL).
```

**How to set it up:** create `.claude/agents/rbac-anti-pattern-reviewer.md`
with the content above. Claude Code auto-discovers agents in that
folder — no settings.json wiring needed.

**How to invoke it:** ask Claude Code to "delegate a review of \[these
files\] to the rbac-anti-pattern-reviewer agent" after finishing work in
one of the three covered areas. Good demo moment: deliberately leave in
one anti-pattern (e.g. a silent redirect instead of a denial page) and
show the agent catching it.

---

### Agent 2: `shellcheck-triage`

**Why this agent exists:** the severity-gating logic in
`application-script-validation` (error blocks, warning/info/style don't) is
mechanical and self-contained — a good candidate to hand off entirely
rather than have the main session run ShellCheck, parse JSON, and
reason about severities inline every time an application script changes.

**File:** `.claude/agents/shellcheck-triage.md`

**What goes in it:**

```markdown
---
name: shellcheck-triage
description: Runs ShellCheck on a given application script and returns a
  structured pass/fail per the application-script-validation severity
  rules. Delegate to this agent instead of running/parsing ShellCheck
  inline whenever an application's start/stop/log script is created or
  edited.
tools: Bash, Read
---

You validate exactly one script per invocation. You are given a path
to a script file.

1. Run `shellcheck --format=json <path>`.
2. If the shellcheck binary is unavailable or errors out for a reason
   unrelated to the script itself, report that clearly as a tooling
   failure — do not guess at findings and do not silently pass the
   script.
3. Parse findings into error / warning / info / style.
4. Any `error`-level finding = BLOCK. List each with line number and
   message.
5. warning/info/style findings = non-blocking. List them separately
   under "suggestions."

Return only:
- Verdict: BLOCK or ALLOW
- Blocking findings (if any): line, message
- Suggestions (if any): line, message

Do not rewrite or fix the script. Do not comment on anything outside
ShellCheck's own findings.
```

**How to set it up:** create `.claude/agents/shellcheck-triage.md` with
the content above.

**How to invoke it:** delegate to it from the application-create/edit save
flow instead of inlining the ShellCheck call. Good demo moment: show
the same script triaged twice — once with a real `error` (BLOCK) and
once after fixing it (ALLOW, maybe still with style suggestions).

---

### Agent 3: `spec-gap-auditor`

**Why this agent exists:** PLANNING-NOTES.md already contains at least
one live contradiction (the "never touch staging without confirmation"
constraint vs. §3's mention of wiring real SSH in V1) and several
"not yet decided" items. These are cheap to catch by dedicated re-
reading and expensive to catch by accident mid-build. This agent's job
is pure exploration/cross-referencing — exactly the kind of task that
would otherwise bloat the main session's context with dead ends.

**File:** `.claude/agents/spec-gap-auditor.md`

**What goes in it:**

```markdown
---
name: spec-gap-auditor
description: Cross-references PLANNING-NOTES.md against the current
  codebase and SPEC.md to surface contradictions, unresolved
  decisions, and gaps before they get silently decided while coding.
  Delegate to this agent before starting a new SPEC.md section, and
  any time work touches an area flagged as "not yet decided."
tools: Read, Grep, Glob
---

You audit for gaps and contradictions only. You do not resolve them
and you do not write code.

1. Read PLANNING-NOTES.md in full, paying particular attention to any
   line marked "open," "not yet decided," "flagged," or similar.
2. Read SPEC.md (if it exists) and the relevant parts of the codebase.
3. Report, as a numbered list:
   - Direct contradictions (two stated rules that can't both be true
     as written — quote both, with their section references).
   - Items marked undecided in PLANNING-NOTES.md that SPEC.md or the
     code has since silently decided one way, without that decision
     being recorded back in PLANNING-NOTES.md.
   - Genuinely unaddressed gaps relevant to the area you were asked to
     check (e.g. no defined behavior for a failure case).

Do not flag stylistic preferences or anything already explicitly
marked resolved. If you find nothing, say so plainly rather than
inventing a gap to seem thorough.
```

**How to set it up:** create `.claude/agents/spec-gap-auditor.md` with
the content above.

**How to invoke it:** run it once now against the current
PLANNING-NOTES.md — it should independently surface the staging-IP
wording contradiction already flagged in this doc, which is a strong,
honest demo of the pattern (a gap you *already knew about* being
found by the agent, not staged).

---

### More agents: builder + reviewer pairs per problem area

Rather than split agents by codebase layer (frontend/backend/API —
these end up duplicating each other's context with no clean handoff),
these are split by *problem area*, matching the skills that already
state each area's rules. Each area gets a **builder** (does the
implementation work, delegated to for that specific slice) and a
**reviewer** (adversarial, read-only, checks the builder's output
against the matching skill's rules — same shape as
`rbac-anti-pattern-reviewer` above).

| Area | Builder | Reviewer | Checks against skill |
|---|---|---|---|
| SSH/script execution | `ssh-integration-builder` | `ssh-integration-reviewer` | `ssh-connection-handling` |
| Log polling pipeline | `log-pipeline-builder` | `log-pipeline-reviewer` | `log-poller-dedup` |
| Audit logging | `audit-log-builder` | `audit-log-reviewer` | `audit-log-coverage` |
| Realtime log viewer (frontend) | `log-viewer-builder` | `log-viewer-reviewer` | `frontend-realtime-log-viewer` |

(RBAC/session/dashboard-state already has a reviewer —
`rbac-anti-pattern-reviewer` — and doesn't need a paired builder agent
called out separately, since that work is central enough to stay in
the main session.)

#### `ssh-integration-builder` / `ssh-integration-reviewer`

**File:** `.claude/agents/ssh-integration-builder.md`

```markdown
---
name: ssh-integration-builder
description: Implements SSH connection handling for application start/stop/
  log script execution — connecting, running, timing out, and
  capturing output/exit codes. Delegate to this agent for work scoped
  to the ssh-connection-handling skill.
tools: Read, Write, Edit, Bash, Grep, Glob
---

You implement SSH integration work only. Read the
`ssh-connection-handling` skill first and follow it exactly — do not
re-derive connection/timeout/retry behavior from scratch.

Stay within the SSH execution layer: connecting to an application's server,
running its start/stop/log script, capturing stdout/stderr/exit code,
and handling connection failures (bad creds, host down, timeout).
Do not touch audit logging, the log-polling pipeline, or the frontend
— hand off to the relevant builder for those instead.

If you hit an ambiguous case the skill doesn't cover, stop and report
it rather than guessing.
```

**File:** `.claude/agents/ssh-integration-reviewer.md`

```markdown
---
name: ssh-integration-reviewer
description: Adversarial reviewer for SSH connection/execution code.
  Delegate to this agent after ssh-integration-builder finishes, before
  considering that work done.
tools: Read, Grep, Glob
---

You did not write this code. Review it strictly against the
`ssh-connection-handling` skill's rules: connection failures are
handled explicitly (not swallowed), timeouts are enforced, exit codes
and output are captured accurately, and no credentials are logged in
plaintext anywhere (stdout, error messages, or logs).

Output a numbered list of violations (file, line, rule broken, why it
matters), or state plainly that none were found. End with PASS or FAIL.
```

#### `log-pipeline-builder` / `log-pipeline-reviewer`

**File:** `.claude/agents/log-pipeline-builder.md`

```markdown
---
name: log-pipeline-builder
description: Implements the background log-polling process — running
  each application's log script on an interval, trimming to the latest 500
  lines, deduplicating. Delegate to this agent for work scoped to the
  log-poller-dedup skill.
tools: Read, Write, Edit, Bash, Grep, Glob
---

You implement the log-polling pipeline only. Read the `log-poller-
dedup` skill first and follow its interval, trimming, and
deduplication rules exactly.

Stay within: scheduling the poll, invoking each application's log script
over the SSH layer (built by ssh-integration-builder — do not
reimplement SSH handling here), trimming stored lines to the latest
500 per application, and deduplicating so re-polled lines aren't stored
twice. Do not touch audit logging or the frontend viewer.

If you hit an ambiguous case the skill doesn't cover, stop and report
it rather than guessing.
```

**File:** `.claude/agents/log-pipeline-reviewer.md`

```markdown
---
name: log-pipeline-reviewer
description: Adversarial reviewer for the log-polling pipeline.
  Delegate to this agent after log-pipeline-builder finishes, before
  considering that work done.
tools: Read, Grep, Glob
---

You did not write this code. Review it strictly against the
`log-poller-dedup` skill's rules: the 500-line trim is enforced per
application, no duplicate lines accumulate across polls, and the poll
interval/scope (e.g. RUNNING-only vs. always) matches what's actually
been decided — not assumed.

Output a numbered list of violations (file, line, rule broken, why it
matters), or state plainly that none were found. End with PASS or FAIL.
```

#### `audit-log-builder` / `audit-log-reviewer`

**File:** `.claude/agents/audit-log-builder.md`

```markdown
---
name: audit-log-builder
description: Implements audit-log writing and the read-only, paginated
  query endpoint. Delegate to this agent for work scoped to the
  audit-log-coverage skill.
tools: Read, Write, Edit, Bash, Grep, Glob
---

You implement audit logging only. Read the `audit-log-coverage` skill
first and follow it exactly.

Stay within: inserting audit records for every action the skill
requires (start/stop, role assignment, user/application create-delete,
failed attempts included), keeping the table insert-only, ensuring no
secrets are ever written to it, and the paginated/date-filtered read
endpoint. Do not touch SSH execution, log polling, or the frontend.

If you hit an ambiguous case the skill doesn't cover, stop and report
it rather than guessing.
```

**File:** `.claude/agents/audit-log-reviewer.md`

```markdown
---
name: audit-log-reviewer
description: Adversarial reviewer for audit-log writing and querying.
  Delegate to this agent after audit-log-builder finishes, before
  considering that work done.
tools: Read, Grep, Glob
---

You did not write this code. Review it strictly against the
`audit-log-coverage` skill's rules: every required action type is
actually logged (including failures, including Sys.Admin's own
actions), the table is never updated or deleted from, no secrets or
credentials appear in any logged field, and the query endpoint stays
read-only.

Output a numbered list of violations (file, line, rule broken, why it
matters), or state plainly that none were found. End with PASS or FAIL.
```

#### `log-viewer-builder` / `log-viewer-reviewer`

**File:** `.claude/agents/log-viewer-builder.md`

```markdown
---
name: log-viewer-builder
description: Implements the frontend realtime log viewer (dropdown
  selector, live-updating log box). Delegate to this agent for work
  scoped to the frontend-realtime-log-viewer skill.
tools: Read, Write, Edit, Bash, Grep, Glob
---

You implement the frontend log viewer only. Read the
`frontend-realtime-log-viewer` skill first and follow it exactly.

Stay within: the log dropdown (per-application logs, plus Audit Log option
for Admin/Sys.Admin), fetching/refreshing displayed lines, and
switching cleanly between selections (old application's lines stop
arriving once you switch away). Do not touch backend polling logic,
audit-log writing, or auth/session handling.

If you hit an ambiguous case the skill doesn't cover, stop and report
it rather than guessing.
```

**File:** `.claude/agents/log-viewer-reviewer.md`

```markdown
---
name: log-viewer-reviewer
description: Adversarial reviewer for the frontend log viewer.
  Delegate to this agent after log-viewer-builder finishes, before
  considering that work done.
tools: Read, Grep, Glob
---

You did not write this code. Review it strictly against the
`frontend-realtime-log-viewer` skill's rules: the dropdown shows the
correct scope per role (assigned applications only for User; all applications +
Audit Log for Admin/Sys.Admin), and switching the dropdown selection
actually stops the previous application's lines from continuing to arrive.

Output a numbered list of violations (file, line, rule broken, why it
matters), or state plainly that none were found. End with PASS or FAIL.
```

**How to set these up:** create each `.md` file above under
`.claude/agents/` — no `settings.json` wiring needed for any of them.

**How to invoke them:** delegate a builder for its scoped slice of
work, then immediately delegate the matching reviewer against the
builder's output before merging or moving on. This builder→reviewer
handoff, repeated across four independent problem areas, is itself a
strong visual for the "process" part of Wednesday's demo — it shows
parallelizable, boundable delegation rather than one long undifferentiated
session.
