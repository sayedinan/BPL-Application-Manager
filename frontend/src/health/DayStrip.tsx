import { useEffect, useState } from 'react';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import type { UptimeBars } from './types';
import { barTitle, browserZone, time } from './uptimeFormat';

type Bar = UptimeBars['bars'][number];

// The strip and its column header share these, so the hour labels line up with the ticks.
const GAP = 'gap-2';
const PCT_WIDTH = 'w-[4.75rem] whitespace-nowrap'; // fits "100.0% up" on one line

/**
 * Compact "today" strip for a Dashboard row: one tick per hour since midnight
 * in the viewer's timezone (the same 'day' range UptimeBar uses).
 * Status is encoded in height as well as color, so it survives grayscale:
 *   tall red tick = checks failed that hour, short green tick = all passed,
 *   hairline = no data, fainter hairline = the hour has not happened yet.
 */
function tickClass(b: Bar): string {
  if (b.future) return 'h-px bg-rule/50';
  if (b.checks === 0) return 'h-1 bg-rule';
  if (b.failedChecks > 0) return 'h-5 bg-danger';
  return 'h-3 bg-ok';
}

/** Hours that had failed checks, e.g. "1:00 AM, 2:00 AM", for the screen-reader label. */
function failedHours(bars: Bar[]): string {
  return bars
    .filter((b) => !b.future && b.failedChecks > 0)
    .map((b) => time(new Date(b.start)))
    .join(', ');
}

/** Hour axis for the column header: 00, 06, 12, 18 over the strip's 24 ticks. */
export function DayAxis(): JSX.Element {
  return (
    <div className={`flex ${GAP}`} aria-hidden="true">
      <div className="relative h-4 flex-1">
        {['00', '06', '12', '18'].map((h, i) => (
          <span key={h} className="tabular absolute top-0" style={{ left: `${i * 25}%` }}>
            {h}
          </span>
        ))}
      </div>
      <span className={PCT_WIDTH} />
    </div>
  );
}

export function DayStrip({ applicationId }: { applicationId: number }): JSX.Element {
  const [data, setData] = useState<UptimeBars | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const tz = browserZone();
    const load = () =>
      api
        .get<UptimeBars>(API.APPLICATIONS.HEALTH_UPTIME(applicationId, 'day', tz))
        .then((d) => {
          if (cancelled) return;
          setData(d);
          setFailed(false);
        })
        .catch(() => {
          // Keep the last good data; only flag the failure if we never had any.
          if (!cancelled) setFailed(true);
        });
    void load();
    const timer = setInterval(() => void load(), 60000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [applicationId]);

  if (!data) {
    return failed ? (
      <p className="text-xs text-muted">History unavailable</p>
    ) : (
      <div className="h-5 w-full animate-shimmer rounded bg-gradient-to-r from-sunken via-rule/60 to-sunken bg-[length:200%_100%]" aria-hidden="true" />
    );
  }

  const pct = data.availabilityPercent;
  const pctTone = pct === null ? 'text-muted' : pct >= 99 ? 'text-ok' : pct >= 90 ? 'text-warn' : 'text-danger';
  const hours = failedHours(data.bars);
  const summary =
    pct === null
      ? 'Today: no checks recorded yet'
      : `Today: ${pct.toFixed(1)}% available.${hours ? ` Checks failed in the hours starting ${hours}.` : ' No failed checks.'}`;

  return (
    <div className={`flex items-end ${GAP}`}>
      <div role="img" aria-label={summary} className="flex h-5 flex-1 items-end gap-[2px]">
        {data.bars.map((b, i) => (
          <div key={i} title={barTitle(b, true)} className={`min-w-0 flex-1 rounded-[1px] ${tickClass(b)}`} />
        ))}
      </div>
      <span
        className={`${PCT_WIDTH} shrink-0 text-right text-xs font-medium tabular ${pctTone}`}
        title="Share of today's health checks that passed"
      >
        {pct === null ? '—' : `${pct.toFixed(1)}% up`}
      </span>
    </div>
  );
}
