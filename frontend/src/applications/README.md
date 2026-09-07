# `applications/`

Sys.Admin-only `/applications` CRUD (SPEC §4.3, §9.2). Edit form is
locked while the application is `RUNNING` / `STARTING` / `STOPPING`
(SPEC §6.4) — every field, not just the "sensitive" ones.

Planned files:

- `ApplicationsListPage.tsx` — table of all applications
- `ApplicationFormPage.tsx` — create/edit, with the
  Test-Connection → Trust-on-first-connect flow (SPEC §12.3)
- `components/ScriptEditor.tsx` — start/stop/log script fields,
  with inline ShellCheck error display (`SHELLCHECK_FAILED`,
  SPEC §4.2)
- `hooks/useApplication.ts`, `hooks/useCreateApplication.ts`, etc.
- `schemas/applicationSchema.ts` — Zod schema for the form
  (React Hook Form + Zod, SPEC §9.1)
