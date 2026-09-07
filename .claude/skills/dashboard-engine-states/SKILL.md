---
name: dashboard-engine-states
description: Dashboard empty states by role, the access-denial page for unauthorized direct navigation, and locking engine edit forms while the engine is RUNNING/STARTING/STOPPING.
---

# Dashboard and engine state handling

## Empty states (differ by role — do not use one generic message)

- **User with zero assigned engines:** show a plain informational message (e.g. "No engines assigned to you yet. Contact your admin for access.") — no call-to-action button. A User cannot self-assign engines, so a CTA here would be misleading.
- **Sys.Admin with zero engines configured:** show a message plus an "Add Engine" call-to-action, since Sys.Admin is exactly the role that can act on this state immediately.

## Access denial (explicit page, not a silent redirect)

If a logged-in user directly navigates (via URL) to a route their role cannot use — e.g. a USER hitting `/engines` or `/users` — render a clear "you don't have access" page. Do NOT silently redirect back to the Dashboard with no explanation. An explicit denial page is more honest about what happened and easier to support/debug than a silent bounce that leaves the user wondering why nothing happened.

## Locking the engine record while RUNNING/STARTING/STOPPING

Per PLANNING-NOTES.md §9.1: the **entire** engine edit form (name, server IP, SSH credentials, all three scripts, poll interval — every field, not just the "sensitive" ones) is locked/disabled whenever that engine's status is `RUNNING`, `STARTING`, or `STOPPING`. It only becomes editable again once the engine is `STOPPED` or `ERROR`. This is a whole-record lock, not a partial one — do not allow editing "safe" fields like the display name while the engine is active.

The Dashboard card itself should also reflect the transitional states visibly: show a distinct visual treatment (e.g. spinner, disabled buttons) during `STARTING`/`STOPPING`, not just a plain status label.

## Anti-patterns to avoid

- Do NOT show the same empty-state message to both User and Sys.Admin — the CTA is role-specific.
- Do NOT silently redirect on unauthorized direct navigation — always show an explicit denial page.
- Do NOT allow partial editing of an engine's record while it's active — the lock applies to the whole record, including seemingly harmless fields like the name.
- Do NOT render `STARTING`/`STOPPING` with the same static appearance as `RUNNING`/`STOPPED` — they need a visibly distinct in-progress state.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test confirming the User empty state has no CTA while the Sys.Admin empty state does, a test confirming a USER navigating directly to `/engines` sees a denial page rather than being silently redirected, and a test confirming every field in the engine edit form is disabled while status is RUNNING.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.