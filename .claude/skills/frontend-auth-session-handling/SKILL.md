---
name: frontend-auth-session-handling
description: How the frontend hydrates auth state on load, handles 401/expired sessions, and enforces the mustChangePassword redirect lock. Session-based auth via HttpOnly cookie, not JWT.
---

# Frontend auth & session handling

## Hydrating auth state on page load

On every app load (including a hard refresh), call `GET /api/auth/me` before rendering any protected page. This confirms whether a valid session cookie exists and returns the current user's `id`, `username`, `role`, and `assignedEngineIds`. Do NOT assume a user is logged in based only on local/client-side state — the cookie-backed session on the server is the source of truth.

## Handling a missing/expired session

If `GET /api/auth/me` returns 401 (no valid session), redirect to the login page. This should happen automatically on any API call that returns 401 while the user is on a protected page mid-session too — not just on initial load — since a session can expire while the app is open.

## The mustChangePassword redirect lock

If the hydrated user has `mustChangePassword = true` (see `rbac-and-engine-assignment` skill for the full flow), the frontend must redirect to the change-password screen and **prevent navigation to any other page** until the flag is cleared. This applies immediately after login and also on any page-load hydration while the flag is still true (e.g. the user closed the tab mid-flow and came back later).

## Login flow

1. Submit credentials to the login endpoint.
2. On success, re-fetch (or use the login response's) user data.
3. If `mustChangePassword = true`, redirect to the change-password screen (see above). Otherwise, redirect to the Dashboard.

## Anti-patterns to avoid

- Do NOT store the session/auth state only in client-side memory or localStorage as the source of truth — always confirm against `GET /api/auth/me` on load, since the actual session lives server-side in the cookie.
- Do NOT allow any page other than the change-password screen to render while `mustChangePassword = true`.
- Do NOT silently fail on a 401 mid-session — always redirect to login.
- Do NOT implement any part of this as if it were JWT-based (no token refresh logic, no client-side token expiry checking — the server-side session is authoritative).

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that a hard refresh with an expired session redirects to login, a test that `mustChangePassword = true` blocks navigation to the Dashboard even via direct URL, and a test that a 401 received mid-session (not just on load) triggers a redirect to login.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.