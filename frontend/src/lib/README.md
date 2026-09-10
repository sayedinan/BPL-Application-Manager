# `lib/`

Pure utilities with no React or API dependencies:

- `time.ts` — UTC ↔ local conversions (SPEC §15 acceptance: "All
  timestamps UTC in DB, local in UI")
- `validation.ts` — shared Zod helpers
- `roles.ts` — the `Role` type and the SPEC §2 permission matrix
  (so feature code can `if (canManageUsers(role))` instead of
  re-deriving role rules)
