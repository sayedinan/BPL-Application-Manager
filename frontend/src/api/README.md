# `api/`

Shared transport layer:

- `client.ts` — fetch wrapper that:
  - sends cookies (`credentials: include`, SPEC §8.4)
  - throws a typed error on RFC 7807 responses (SPEC §4.1)
  - **redirects to `/login` on any 401, including mid-session**
    (SPEC §8.1)
  - surfaces the SPEC §4.2 `code` field on thrown errors so feature
    code can switch on it (`SHELLCHECK_FAILED`,
    `APPLICATION_ALREADY_RUNNING`, etc.)
- `endpoints.ts` — typed path constants for the SPEC §4.3 endpoints
- `schemas/` — Zod schemas that mirror the backend's RFC 7807
  envelope and per-endpoint response shapes
- `stompClient.ts` — single STOMP-over-WebSocket client with
  exponential-backoff reconnect (1/2/4/8/16/30s, 10 attempts, then
  banner — SPEC §5.3)
