/**
 * True when a scroll container is at (or within `threshold` px of) its bottom.
 *
 * The maximum scrollTop is scrollHeight - clientHeight, so the distance from the
 * bottom is scrollHeight - scrollTop - clientHeight. Comparing scrollTop against
 * scrollHeight alone (ignoring clientHeight) reports "not at bottom" even when
 * fully scrolled down, which would pause a live log's auto-follow immediately.
 */
export function isNearBottom(
  el: { scrollTop: number; scrollHeight: number; clientHeight: number },
  threshold = 50,
): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
}
