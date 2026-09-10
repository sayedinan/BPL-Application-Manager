---
name: engine-script-validation
description: Rules for validating Sys.Admin-authored start/stop/log scripts using ShellCheck before an engine is saved. Covers severity gating and what happens if ShellCheck itself is unavailable.
---

# Engine script validation

Every engine's start/stop/log script is validated with ShellCheck at save time (create or edit), run **locally on the backend** — never against the remote engine server. This is pure static analysis; no SSH connection is needed to validate a script's syntax/correctness.

## Staging server confirmation (separate from ShellCheck, same save flow)

If the `serverIp` field on the Engine form matches the known live staging address (`STAGING_IP_PLACEHOLDER`, shared with the JMeter integration suite), do NOT block saving — but DO show an explicit confirmation dialog before the save completes (e.g. "This is the shared JMeter staging server — confirm you intend to point a managed engine at it"). This is a deliberate-action safeguard, not a hard block: pointing an engine at this address is the legitimate final use of the finished product, so it must remain possible, just never accidental. See PLANNING-NOTES.md §12.2 for the full reasoning.

## Invocation

Run: `shellcheck --format=json <script-file>`

Parse the JSON output into a list of findings, each with a `level` (error / warning / info / style), a line number, and a message.

## Severity gating (this is the part most likely to be gotten wrong)

- **`error` level findings BLOCK saving.** Show each one inline, with its line number and message, next to the relevant script field. The user cannot save until these are fixed.
- **`warning` / `info` / `style` findings do NOT block saving.** Show them as non-blocking suggestions (e.g. a collapsible "3 suggestions" section), but allow Save to proceed.
- Do not treat all ShellCheck findings as equally blocking. ShellCheck's stricter checks are often stylistic opinions, not real bugs — blocking on all of them would frustrate a developer trying to ship a working script for no real safety benefit.

## If ShellCheck itself can't run (fail open — NOT fail closed)

This is a different situation from the script having errors. If the `shellcheck` binary is missing, or the process call itself fails for an environment reason (permission denied, binary not found, crash) — this means the checking tool is unavailable, not that the script is bad.

**In this case: allow Save to proceed anyway.** Do not block engine creation/editing just because the validation tool had a problem. But:

- Show a clear, visible warning in the UI at save time, e.g.: `⚠ Script validation unavailable — saved without checking.`
- Write an audit log entry noting that this particular save happened without validation.

This is a deliberate choice, not an oversight: ShellCheck being unavailable is a one-time setup problem that shouldn't be able to fully block using the app while it gets fixed. It is also not the only safety net — a genuinely broken script will still surface later as a `SSH_COMMAND_FAILED` error when the engine is actually started.

## Anti-patterns to avoid

- Do NOT validate the script against the remote engine server via SSH — this check is local static analysis only.
- Do NOT block Save on warning/info/style findings.
- Do NOT block Save if ShellCheck itself fails to run — fail open with a visible warning instead (see above).
- Do NOT silently skip validation without telling the user — the warning must be visible, not just logged server-side.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that an `error`-severity finding blocks save, a test that a `warning`-only finding does NOT block save, a test that a simulated ShellCheck-unavailable condition still allows save (with the warning shown/logged).
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.