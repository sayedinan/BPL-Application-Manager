---
name: rbac-and-engine-assignment
description: The three-tier role model (Sys.Admin/Admin/User), the separate "assigned engines" concept, and the forced-password-change flow for new accounts. Clarifies terminology that is easy to conflate.
---

# RBAC tiers and engine assignment

## Two different concepts — do not use the word "role" for both

- **"Role"** means ONLY the RBAC tier: `SYS_ADMIN`, `ADMIN`, or `USER`. One column on the `users` table: `role`.
- **"Assigned engines"** (never "role") is the separate concept of which specific engines a User can see/start/stop/view logs for. This is a many-to-many relationship, stored in a table named `user_engine_assignments` (not `user_engine_roles` — that name was considered and rejected specifically to avoid the naming collision).

If you are about to write or say "the user's role is BPL" — stop; the correct phrasing is "the user is **assigned** the BPL engine." `SYS_ADMIN`/`ADMIN`/`USER` are the only valid values for the word "role" anywhere in this codebase.

## The three-tier permission matrix

| Action | SYS_ADMIN | ADMIN | USER |
|---|---|---|---|
| Create/delete users | ✅ | ✅ (User role only) | ❌ |
| Create/delete Admins | ✅ | ❌ | ❌ |
| Add/delete/edit engines | ✅ | ❌ | ❌ |
| See all engines | ✅ | ✅ | ❌ (assigned only) |
| Start/stop engines | ✅ (all) | ✅ (all) | ✅ (assigned only) |
| See audit log | ✅ | ✅ | ❌ |
| See engine logs | ✅ (all) | ✅ (all) | ✅ (assigned only) |
| Assign engines to users | ✅ | ✅ | ❌ |

Admin's access to engines/users is **global**, not scoped to any subset — an earlier idea to scope Admin per-engine was explicitly discarded during planning.

## Forced password change on account creation

Every new account (including the one-time seeded first SYS_ADMIN) is created with `mustChangePassword = true`. On login, if this flag is true, the user must be routed to a change-password screen before reaching anything else in the app — no skipping, no navigating away. Once changed, the flag becomes false. If an admin later resets someone's password, that action should set the flag back to true.

## Anti-patterns to avoid

- Do NOT use the word "role" to describe engine access — use "assigned engines" / "engine assignment."
- Do NOT name the join table anything containing the word "role" — it's `user_engine_assignments`.
- Do NOT scope Admin's access to a subset of engines — Admin sees/controls all engines, same as Sys.Admin (only engine CRUD and user-tier creation differ between them).
- Do NOT allow a user with `mustChangePassword = true` to reach any page other than the change-password screen.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test confirming a USER-role request to an Admin/Sys.Admin-only endpoint is rejected, a test confirming Admin's engine visibility is NOT scoped to a subset, and a test confirming a user with `mustChangePassword = true` is redirected away from every page except the change-password screen.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.