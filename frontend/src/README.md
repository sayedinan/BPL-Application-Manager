# `src/`

React 18 + TypeScript + Vite application (SPEC §9.1).

Feature-based folder layout that mirrors the backend's module
boundaries. Each feature folder is self-contained; `app/`, `api/`,
`common/`, `lib/`, and `styles/` hold cross-cutting concerns.

| Folder | SPEC anchor |
|---|---|
| `app/` | Top-level shell, router, providers |
| `auth/` | §4.3 Auth, §8.1 session handling, `mustChangePassword` lock |
| `dashboard/` | §9.2 `/`, §9.3 layout, §9.4 empty states |
| `applications/` | §4.3 Applications (Sys.Admin CRUD) |
| `users/` | §4.3 Users (Admin+ management) |
| `audit-logs/` | §4.3 Audit Logs |
| `api/` | Shared fetch + STOMP transport, RFC 7807 error handling |
| `common/` | Shared UI primitives, 403 page |
| `lib/` | Pure utilities, role permission matrix |
| `styles/` | Global CSS / Tailwind |
