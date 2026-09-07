---
name: audit-log-coverage
description: Which actions must write an audit log entry, how audit entries are written (aspect-based, not manual calls), and the permanence rules that keep the audit log trustworthy.
---

# Audit log coverage

## Mechanism: aspect/annotation-based, not manual calls

State-changing methods are marked with an annotation (e.g. `@Audited(action = START_ENGINE)`). A single aspect wraps every annotated method, extracts the current actor from the session, captures whether the action succeeded or failed, and writes the audit row automatically.

**Do NOT write audit-log calls manually inside individual service methods.** The whole point of the aspect approach is that no state-changing action can be accidentally left out of the audit trail because a developer forgot to add a call — new annotated methods are covered automatically.

## What counts as a state-changing action requiring the annotation

- Engine: create, edit, delete, start, stop
- User: create, edit (role or assigned-engines change), delete
- Auth: login, logout, password change (including the forced first-login change from the mustChangePassword flow)

Read-only actions (viewing the dashboard, viewing logs, viewing the audit log itself) do NOT get audited — only actions that change state.

## Permanence rules (do not weaken these)

- Audit rows are **insert-only**: never edited, never deleted, by anything, under any circumstances — including when the thing they reference (an engine, a user) is later deleted.
- When an **engine** is deleted: its audit log entries are left completely untouched. The engine's numeric ID stored in the audit row is a **plain value, not an enforced foreign key** — because engine IDs are never reused, it's always safe for this value to keep pointing at an ID that no longer exists in the engines table. Do not add an `ON DELETE CASCADE` or `ON DELETE SET NULL` constraint here; there should be no foreign-key constraint enforced on this column at all.
- Every audit row also stores a **plain-text snapshot of the relevant engine/user name** at the time of the action (not solely an ID reference), so historical entries remain human-readable (e.g. "Started BPL Order Engine") even after the referenced engine or user no longer exists.

## Anti-patterns to avoid

- Do NOT write audit rows via scattered manual calls in service code — use the annotation + aspect mechanism.
- Do NOT add any code path that edits or deletes an existing audit row.
- Do NOT cascade-delete audit rows when an engine or user is deleted.
- Do NOT rely solely on a foreign key / ID reference for readability — always also store the plain-text name snapshot.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that every annotated state-changing endpoint produces exactly one audit row per call, a test that deleting an engine leaves its existing audit rows completely intact (row count and content unchanged), and a test that no code path can edit or delete an existing audit row.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.