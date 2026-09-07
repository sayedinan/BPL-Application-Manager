---
name: ssh-connection-handling
description: How SSH connections to engine servers are opened, reused, and cleaned up. Covers the reuse-with-lazy-reconnect strategy, timeouts, and failure handling.
---

# SSH connection handling

## Core rule: reuse, don't reconnect every call

Keep **one cached SSH connection per engine**, keyed by engine ID. Do NOT open a fresh connection for every start/stop/log-read call — at a short poll interval (e.g. every 5 seconds per engine, configurable), the overhead of a new TCP handshake + SSH negotiation + auth every single call is real and avoidable, and it compounds as more engines are added.

## When to reuse vs. reconnect

Before each use (a poll tick, a start call, a stop call):
1. Check if the cached connection for this engine is still alive.
2. If alive, use it directly.
3. If dead or missing, open a fresh connection and cache it.

## Failure handling — the important distinction

There are two very different kinds of failure, and they must be handled differently:

- **Connection-level failure** (broken pipe, timeout, connection refused, auth rejected at the transport level) — this means the cached connection itself is bad. **Invalidate the cache** so the next attempt opens a fresh connection instead of repeatedly failing against a dead handle.
- **Script-level failure** (the command ran, but exited non-zero) — the connection is perfectly fine; only the script failed. This is a normal `SSH_COMMAND_FAILED` result. **Do NOT invalidate the connection cache for this** — the connection worked, it just carried back a script error.

Conflating these two is the most likely mistake here: treating an ordinary non-zero script exit as a reason to throw away a perfectly good cached connection would cause unnecessary reconnect overhead on every routine script failure.

## Cleanup

On engine STOP or engine deletion: explicitly close and discard that engine's cached connection. No orphaned connections should linger after an engine stops being active (engine deletion already requires STOPPED first — see PLANNING-NOTES.md §9.1).

## Timeouts (configurable, not hardcoded — these are sensible defaults)

- Connect timeout: 5 seconds
- Command timeout for start/stop scripts: 30 seconds (these can reasonably take a while)
- Command timeout for log-read commands: ~10 seconds (should be fast; a hang here shouldn't block the log poller indefinitely)

## Anti-patterns to avoid

- Do NOT open a new connection for every single call — reuse per engine.
- Do NOT invalidate the cached connection just because a script exited non-zero — only invalidate on genuine connection-level failures.
- Do NOT leave a connection open after an engine is stopped or deleted.
- Do NOT hardcode timeout values inline scattered across the codebase — keep them as named, configurable constants in one place.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that two calls within the timeout window reuse one connection, a test that a connection-level failure invalidates the cache, and a test that a script-level (non-zero exit) failure does NOT invalidate the cache.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.