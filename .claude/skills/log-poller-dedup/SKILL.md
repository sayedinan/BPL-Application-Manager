---
name: log-poller-dedup
description: How the background log poller determines which lines from a freshly-run log script are new, avoiding duplicates without requiring the script itself to support incremental output.
---

# Log poller deduplication

## The two-layer delivery model

Don't conflate these two different mechanisms:

1. **Initial load** (opening the Logs box, or switching engines in the dropdown): a plain REST call returns whatever's currently stored in the database for that engine — up to the last 500 lines. This is served once, instantly, as history.
2. **Live updates from then on**: delivered over WebSocket, and only contain the **new** lines discovered on each poll tick — small, frequent deltas appended to what's already shown. Never re-send the full 500 lines over the WebSocket; only send what's new.

## The per-tick diffing algorithm

The log script cannot be assumed to support "give me only new lines since last time" — it realistically returns a snapshot window (e.g. `tail -n 500 file.log`) every time it runs. The poller's job is to figure out what's new from that snapshot:

1. Run the log script over SSH → get an ordered list of lines (oldest to newest), whatever window the script returns.
2. Recall the **content of the last line** this poller previously saved for this engine.
3. Search the new batch **from the end, backward**, for that same line content.
   - Search backward, not forward: if that exact text repeats multiple times in the batch (e.g. a generic "heartbeat" message appearing often), matching the **most recent** occurrence minimizes the risk of re-treating already-seen content as new.
4. Everything **after** the matched position is genuinely new. Append those lines to the database with an internally-assigned sequential line number (do not derive the line number from the script's output), trim the table back to 500 rows for that engine, and broadcast only the new lines over WebSocket.
5. **Fallback:** if the last-known line's content is not found anywhere in the new batch (this happens if the log file was rotated/truncated, or enough volume occurred between polls that the old tail scrolled entirely out of the window), treat the **entire new batch as new**. Prefer risking a duplicate line over silently losing content.

## No special-casing needed for "engine just started"

If an engine's log was freshly cleared when it started, the "last known line" from before naturally won't be found in the new batch, and the fallback in step 5 handles it correctly using the exact same logic as any other poll tick. Do not write separate logic for "first poll after start."

## This is heuristic, not a guarantee — and that's accepted

If a script's output contains many genuinely identical lines close together, this algorithm can occasionally misjudge the new/old boundary by a line or two. This is a known, accepted trade-off for an internal admin tool — do not attempt to build a more complex/perfect solution than what's described here; it isn't worth the added complexity.

## Anti-patterns to avoid

- Do NOT require or assume the log script returns only new lines — design for a snapshot-window script.
- Do NOT re-send the full 500-line history over WebSocket on every tick — only send genuinely new lines.
- Do NOT search forward through the batch when looking for the last known line — search backward, for the reason in step 3 above.
- Do NOT treat "last known line not found" as an error condition — it's an expected case (log rotation, etc.) with a defined fallback.

## Testing and when to stop

- Write a test proving each anti-pattern above does NOT happen — e.g. a test with repeated identical lines confirming the backward-search picks the most recent match, a test simulating log rotation/truncation confirming the fallback (treat entire batch as new) fires correctly, and a test confirming only new lines (not the full 500) are broadcast over WebSocket.
- If you hit an error, an ambiguous case, a failing test you can't explain, or a situation this skill doesn't clearly answer while working in this area: **stop coding.** Do not guess and keep going. Report the exact error or ambiguity, propose your best suggested fix or interpretation, and wait for confirmation before proceeding.