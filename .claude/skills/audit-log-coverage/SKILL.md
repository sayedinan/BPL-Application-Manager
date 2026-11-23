---
name: audit-log-coverage
description: Which actions must write an audit log entry, how audit entries are written (explicit `auditWriter.write(...)` calls, outside the caller's transaction), and the permanence rules that keep the audit log trustworthy.
---

# Audit log coverage

## Mechanism: explicit `auditWriter.write(...)` calls

The codebase has no aspect. Each state-changing controller/service method calls `AuditWriter` explicitly (about 20 call sites), recording the actor, the action type, and SUCCESS or FAILURE. `AuditWriter` methods are `@Transactional(propagation = REQUIRES_NEW)` so a failure audit survives a rolled-back business transaction.

**Rule: never call `AuditWriter` from inside an open write transaction.** Commit the business change first (own `@Transactional` service method or `TransactionTemplate`), then audit; write the failure audit in a `catch` outside the transaction. Nested REQUIRES_NEW deadlocks on SQLite (the outer transaction holds the single write lock).

Because calls are manual, every new state-changing action must add its own audit call, and a test must prove it (see below).

## What counts as a state-changing action requiring an audit call

- Engine: create, edit, delete, start, stop
- User: create, edit (role or assigned-engines change), delete
- Auth: login, logout, password change (including the forced first-login change from the mustChangePassword flow)

Read-only actions (viewing the dashboard, viewing logs, viewing the audit log itself) do NOT get audited — only actions that change state.

## Permanence rules (do not weaken these)

- Audit rows are **insert-only**: never edited, never deleted, by anything, under any circumstances — including when the thing they reference (an engine, a user) is later deleted.
- When an **engine** is deleted: its audit log entries are left completely untouched. The engine's numeric ID stored in the audit row is a **plain value, not an enforced foreign key** — because engine IDs are never reused, it's always safe for this value to keep pointing at an ID that no longer exists in the engines table. Do not add an `ON DELETE CASCADE` or `ON DELETE SET NULL` constraint here; there should be no foreign-key constraint enforced on this column at all.
- Every audit row also stores a **plain-text snapshot of the relevant engine/user name** at the time of the action (not solely an ID reference), so historical entries remain human-readable (e.g. "Started BPL Order Engine") even after the referenced engine or user no longer exists.

## Anti-patterns to avoid

- Do NOT leave a state-changing action without an audit call, and do NOT call `AuditWriter` inside an open write transaction.
- Do NOT add any code path that edits or deletes an existing audit row.
- Do NOT cascade-delete audit rows when an engine or user is deleted.
- Do NOT rely solely on a foreign key / ID reference for readability — always also store the plain-text name snapshot.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that every state-changing endpoint produces exactly one audit row per call, a test that deleting an engine leaves its existing audit rows completely intact (row count and content unchanged), and a test that no code path can edit or delete an existing audit row.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.