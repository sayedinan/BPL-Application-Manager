/** Whole seconds since an ISO instant, or null when missing or in the future. */
function secondsSince(startedAt: string | null): number | null {
  if (!startedAt) return null;
  const ms = Date.now() - new Date(startedAt).getTime();
  return ms < 0 ? null : Math.floor(ms / 1000);
}

/**
 * Compact uptime for lists and confirmations: "3d 4h", "19h 2m", "40m", "<1m".
 * Shows the two largest units only, so it changes at most once a minute and
 * never jitters the way a seconds counter does.
 */
export function formatUptime(startedAt: string | null): string | null {
  const total = secondsSince(startedAt);
  if (total === null) return null;
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return '<1m';
}
