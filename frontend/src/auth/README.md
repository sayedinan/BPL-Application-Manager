# `auth/`

Owns the SPEC §4.3 "Auth" routes and the session-handling logic
that wraps the rest of the app:

- `LoginPage.tsx` — credentials → cookie
- `ChangePasswordPage.tsx` — self-service + forced first-login change
- `hooks/useCurrentUser.ts` — `GET /api/auth/me` (SPEC §8.1 hydration
  on every page load, never trust client-side state alone)
- `hooks/useLogin.ts`, `hooks/useLogout.ts`
- `AuthContext.tsx` — current user, role, assigned application IDs
- `guards/RequireAuth.tsx` — redirects to `/login` on missing/expired
  session
- `guards/RequireChangePassword.tsx` — locks all routes except
  `/change-password` while `mustChangePassword = true`
- `guards/RequireRole.tsx` — role-gated routes (SPEC §9.2)

The 401-redirect-on-mid-session-call behavior is wired in the shared
fetch wrapper (see `api/client.ts`) — not handled per-page.
