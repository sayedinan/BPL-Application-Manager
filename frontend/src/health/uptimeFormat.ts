import type { UptimeBars } from './types';

export type UptimeBar = UptimeBars['bars'][number];

/** The viewer's own timezone, so "today" matches the clock they see. Empty = server decides. */
export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
  } catch {
    return '';
  }
}

export const time = (d: Date): string => d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
export const shortDate = (d: Date): string => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

/** Last minute covered by a bar (bars are [start, end), shown as 00:00 to 23:59). */
export const lastMinute = (b: UptimeBar): Date => new Date(new Date(b.end).getTime() - 60_000);

/** Merged time ranges where checks failed, e.g. "12:00 PM – 1:59 PM". */
export function downRanges(b: UptimeBar): string[] {
  const start = new Date(b.start).getTime();
  const size = (new Date(b.end).getTime() - start) / b.slices.length;
  const out: string[] = [];
  let from = -1;
  b.slices.forEach(([, failed], i) => {
    if (failed > 0 && from < 0) from = i;
    if ((failed === 0 || i === b.slices.length - 1) && from >= 0) {
      const last = failed > 0 ? i : i - 1;
      out.push(`${time(new Date(start + from * size))} – ${time(new Date(start + (last + 1) * size - 60_000))}`);
      from = -1;
    }
  });
  return out;
}

export function barTitle(b: UptimeBar, hourly: boolean): string {
  const start = new Date(b.start);
  const when = hourly
    ? `${shortDate(start)}, ${time(start)} – ${time(lastMinute(b))}`
    : start.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
  if (b.future) return `${when} · not yet`;
  if (b.checks === 0) return `${when} · no data`;
  const down = downRanges(b);
  return `${when} · ${(((b.checks - b.failedChecks) / b.checks) * 100).toFixed(1)}% available${
    down.length ? ` · down ${down.join(', ')}` : ''
  }`;
}
