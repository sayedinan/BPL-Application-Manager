import { useEffect, useMemo, useState } from 'react';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { RangeButtons } from './RangeButtons';
import type { HealthHistory } from './types';

const OPTIONS = [
  { key: 'day', label: '1 day', hours: 24, bars: 24, ago: '24 hours ago' }, // 1 bar = 1 hour
  { key: 'week', label: '7 days', hours: 168, bars: 28, ago: '7 days ago' }, // 1 bar = 6 hours
  { key: 'month', label: '30 days', hours: 720, bars: 30, ago: '30 days ago' }, // 1 bar = 1 day
] as const;
type Key = (typeof OPTIONS)[number]['key'];

interface Bar {
  start: number;
  checks: number;
  failed: number;
}

/** Regroups the server's buckets into a fixed number of equal bars. */
function toBars(history: HealthHistory, hours: number, count: number): Bar[] {
  const span = hours * 3600_000;
  const start = Date.now() - span;
  const size = span / count;
  const bars: Bar[] = Array.from({ length: count }, (_, i) => ({ start: start + i * size, checks: 0, failed: 0 }));
  for (const p of history.points) {
    const i = Math.floor((new Date(p.t).getTime() - start) / size);
    if (i >= 0 && i < count) {
      bars[i].checks += p.checks;
      bars[i].failed += p.failedChecks;
    }
  }
  return bars;
}

export function UptimeBar({ applicationId }: { applicationId: number }): JSX.Element {
  const [range, setRange] = useState<Key>('day');
  const [history, setHistory] = useState<HealthHistory | null>(null);
  const opt = OPTIONS.find((o) => o.key === range) ?? OPTIONS[0];

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api
        .get<HealthHistory>(API.APPLICATIONS.HEALTH_HISTORY(applicationId, opt.hours))
        .then((h) => {
          if (!cancelled) setHistory(h);
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
  }, [applicationId, opt.hours]);

  const ready = history !== null && history.hours === opt.hours;
  const bars = useMemo(() => (ready && history ? toBars(history, opt.hours, opt.bars) : []), [ready, history, opt]);
  const pct = ready ? history.availabilityPercent : null;
  const pctColor =
    pct === null ? '' : pct >= 99 ? 'text-status-online' : pct >= 90 ? 'text-status-pending' : 'text-status-error';

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className={`text-sm font-semibold ${pctColor}`}>
          {pct === null ? 'Availability —' : `Availability ${pct.toFixed(1)}%`}
        </span>
        <RangeButtons options={OPTIONS.map((o) => ({ key: o.key, label: o.label }))} value={range} onChange={(k) => setRange(k)} />
      </div>

      <div className="flex h-9 gap-[2px]" role="img" aria-label={`Availability over the last ${opt.label}`}>
        {(bars.length ? bars : Array.from({ length: opt.bars }, () => null)).map((b, i) => (
          <div
            key={i}
            title={
              b && b.checks > 0
                ? `${new Date(b.start).toLocaleString()} · ${(((b.checks - b.failed) / b.checks) * 100).toFixed(1)}% available`
                : b
                  ? `${new Date(b.start).toLocaleString()} · no data`
                  : undefined
            }
            className="flex flex-1 flex-col overflow-hidden rounded-sm bg-slate-300 dark:bg-gh-hover"
          >
            {b && b.checks > 0 && (
              <>
                <div className="bg-status-online" style={{ flex: `${b.checks - b.failed} 1 0%` }} />
                <div className="bg-status-error" style={{ flex: `${b.failed} 1 0%` }} />
              </>
            )}
          </div>
        ))}
      </div>

      <div className="mt-1 flex justify-between text-[11px] text-slate-500 dark:text-gh-muted">
        <span>{opt.ago}</span>
        <span>Now</span>
      </div>
      {ready && history.checks === 0 && (
        <p className="mt-1 text-xs text-slate-500 dark:text-gh-muted">No checks recorded in this period yet.</p>
      )}
    </div>
  );
}
