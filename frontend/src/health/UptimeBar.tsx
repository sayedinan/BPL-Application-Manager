import { useEffect, useMemo, useState } from 'react';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { RangeButtons } from './RangeButtons';
import type { UptimeBars } from './types';

const OPTIONS = [
  { key: 'day', label: '1 day', count: 24 }, // today, 1 bar = 1 hour
  { key: 'week', label: '7 days', count: 7 }, // today + 6 days before, 1 bar = 1 calendar day
  { key: 'month', label: '30 days', count: 30 }, // today + 29 days before, 1 bar = 1 calendar day
] as const;
type Key = (typeof OPTIONS)[number]['key'];
type Bar = UptimeBars['bars'][number];

/** The viewer's own timezone, so "today" matches the clock they see. Empty = server decides. */
function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
  } catch {
    return '';
  }
}

const time = (d: Date): string => d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
const shortDate = (d: Date): string => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
/** Last minute covered by a bar (bars are [start, end), shown as 00:00 to 23:59). */
const lastMinute = (b: Bar): Date => new Date(new Date(b.end).getTime() - 60_000);

function barTitle(b: Bar, hourly: boolean): string {
  const start = new Date(b.start);
  const when = hourly
    ? `${shortDate(start)}, ${time(start)} – ${time(lastMinute(b))}`
    : start.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
  if (b.future) return `${when} · not yet`;
  if (b.checks === 0) return `${when} · no data`;
  return `${when} · ${(((b.checks - b.failedChecks) / b.checks) * 100).toFixed(1)}% available`;
}

export function UptimeBar({ applicationId }: { applicationId: number }): JSX.Element {
  const [range, setRange] = useState<Key>('day');
  const [data, setData] = useState<UptimeBars | null>(null);
  const opt = OPTIONS.find((o) => o.key === range) ?? OPTIONS[0];
  const hourly = opt.key === 'day';

  useEffect(() => {
    let cancelled = false;
    const tz = browserZone();
    const load = () =>
      api
        .get<UptimeBars>(API.APPLICATIONS.HEALTH_UPTIME(applicationId, opt.key, tz))
        .then((d) => {
          if (!cancelled) setData(d);
        })
        .catch(() => {
          // keep what we had
        });
    void load();
    const timer = setInterval(() => void load(), 60000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [applicationId, opt.key]);

  const ready = data !== null && data.range === opt.key;
  const bars: Bar[] = useMemo(() => (ready && data ? data.bars : []), [ready, data]);
  const pct = ready && data ? data.availabilityPercent : null;
  const pctColor =
    pct === null ? '' : pct >= 99 ? 'text-status-online' : pct >= 90 ? 'text-status-pending' : 'text-status-error';

  const leftLabel = bars.length
    ? hourly
      ? time(new Date(bars[0].start))
      : shortDate(new Date(bars[0].start))
    : '';
  const rightLabel = bars.length ? (hourly ? time(lastMinute(bars[bars.length - 1])) : 'Today') : '';

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className={`text-sm font-semibold ${pctColor}`}>
          {pct === null ? 'Availability —' : `Availability ${pct.toFixed(1)}%`}
        </span>
        <RangeButtons options={OPTIONS.map((o) => ({ key: o.key, label: o.label }))} value={range} onChange={(k) => setRange(k)} />
      </div>

      <div className="flex h-9 gap-[2px]" role="img" aria-label={`Availability, ${opt.label}`}>
        {(bars.length ? bars : Array.from({ length: opt.count }, () => null)).map((b, i) => (
          <div
            key={i}
            title={b ? barTitle(b, hourly) : undefined}
            className="flex flex-1 flex-col overflow-hidden rounded-sm bg-slate-300 dark:bg-gh-hover"
          >
            {b && !b.future && b.checks > 0 && (
              <>
                <div className="bg-status-online" style={{ flex: `${b.checks - b.failedChecks} 1 0%` }} />
                <div className="bg-status-error" style={{ flex: `${b.failedChecks} 1 0%` }} />
              </>
            )}
          </div>
        ))}
      </div>

      <div className="mt-1 flex justify-between text-[11px] text-slate-500 dark:text-gh-muted">
        <span>{leftLabel}</span>
        <span>{rightLabel}</span>
      </div>
      {ready && data && data.checks === 0 && (
        <p className="mt-1 text-xs text-slate-500 dark:text-gh-muted">No checks recorded in this period yet.</p>
      )}
    </div>
  );
}