---
name: frontend-realtime-log-viewer
description: Behavior of the Dashboard's Logs box — auto-scroll/pause/jump-to-latest, and WebSocket subscribe/unsubscribe when the dropdown selection changes.
---

# Frontend realtime log viewer

## Initial load vs. live updates

- On opening the Logs box, or selecting a new entry in its dropdown: fetch the initial history via `GET /api/engines/{id}/logs` (a normal REST call) and render it immediately.
- After that initial render, subscribe to the corresponding WebSocket topic (`/topic/engine-logs/{id}` or `/topic/audit-log`) for live updates. Only append new incoming lines to what's already rendered — never re-fetch or re-render the full history on each new line.

## Dropdown switching — subscription hygiene

When the user selects a different entry in the dropdown:
1. Unsubscribe from the previously-active WebSocket topic first.
2. Clear the currently-rendered lines.
3. Fetch the new selection's history via REST.
4. Subscribe to the new topic.

Do NOT stay subscribed to more than one topic at a time from a single Logs box instance — this would cause lines from the wrong engine (or the audit log) to appear mixed into the wrong view.

## Auto-scroll behavior

- By default, the log viewer auto-follows the bottom (newest line) as new lines arrive.
- If the user manually scrolls up to read older lines, **auto-follow pauses** — new lines still arrive and are stored, but the view does not yank the user back down to the bottom while they're reading.
- While paused, show a "↓ Jump to latest" affordance (e.g. a small floating button). Clicking it scrolls to the bottom and re-enables auto-follow.
- Switching the dropdown selection always resets to the bottom and re-enables auto-follow, regardless of the previous scroll state.

## Anti-patterns to avoid

- Do NOT force-scroll to the bottom on every new line regardless of where the user has scrolled to — this is a well-known UX annoyance for live log viewers.
- Do NOT leave a WebSocket subscription active after switching away from it in the dropdown.
- Do NOT subscribe to a new topic before unsubscribing from the old one — always unsubscribe first to avoid a brief window of mixed data.
- Do NOT re-fetch the full history on every incoming WebSocket message — only fetch history once, on initial load / dropdown switch.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test that scrolling up pauses auto-follow while new lines still accumulate in state, a test that switching the dropdown unsubscribes the old topic before subscribing to the new one, and a test that no more than one topic is subscribed at a time.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.