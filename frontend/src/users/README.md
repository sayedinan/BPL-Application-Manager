# `users/`

Admin+ `/users` management (SPEC §4.3, §9.2). Covers the combined
role + application-assignment update endpoint, the reset-password
flow (returns a one-time temp password, sets
`must_change_password = true` — SPEC §12.2), and the self-delete
guard (`SELF_DELETE_FORBIDDEN`, 403).

Planned files:

- `UsersListPage.tsx`
- `UserFormPage.tsx` — create + edit, with assignment grid
- `ResetPasswordDialog.tsx` — shows the returned temp password once
- `hooks/useUsers.ts`, `hooks/useUpdateUser.ts`
- `schemas/userSchema.ts`
