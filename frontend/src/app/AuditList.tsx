import { Fragment } from 'react';
import { Badge } from '@/components/ui/Badge';
import {
  actionLabel,
  actorLabel,
  detailText,
  isSuccess,
  roleLabel,
  targetLabel,
  type AuditLogRow,
} from '@/app/auditFormat';

const AUDIT_GRID =
  'grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4 gap-y-1 ' +
  'md:grid-cols-[8rem_9rem_minmax(0,1fr)_5.5rem]';

// Times are shown without the date (the day divider carries it), so the column stays narrow.
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' });
const dayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
const dayWithYearFormat = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
});

/** Short zone name of the viewer ("GMT+6", "PST"), so "7:47 PM" is never ambiguous. */
function zoneName(): string {
  try {
    return (
      new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
        .formatToParts(new Date())
        .find((p) => p.type === 'timeZoneName')?.value ?? ''
    );
  } catch {
    return '';
  }
}

const dayKey = (d: Date): string => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;

/** "Today", "Yesterday", or "Sun 22 Nov" (with the year for other years). */
function dayLabel(d: Date): string {
  const now = new Date();
  if (dayKey(d) === dayKey(now)) return 'Today';
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (dayKey(d) === dayKey(yesterday)) return 'Yesterday';
  return (d.getFullYear() === now.getFullYear() ? dayFormat : dayWithYearFormat).format(d);
}

export function AuditListHeader(): JSX.Element {
  const zone = zoneName();
  return (
    <div
      aria-hidden="true"
      className={`${AUDIT_GRID} sticky top-0 z-10 hidden border-b border-rule bg-sunken px-4 py-2 text-xs font-medium text-muted md:grid`}
    >
      <span>{zone ? `Time (${zone})` : 'Time'}</span>
      <span>Who</span>
      <span>What happened</span>
      <span>Result</span>
    </div>
  );
}

/**
 * Audit entries, oldest first (newest at the bottom, where the viewer follows),
 * with a divider whenever the day changes. DOM order is event, result, time, who
 * so a screen reader hears what happened first; md:order-* restores
 * Time, Who, Event, Result visually on wide screens.
 *
 * Only failures get a badge. A green badge on every row buries the one that matters.
 */
export function AuditList({ rows }: { rows: AuditLogRow[] }): JSX.Element {
  let lastDay = '';
  return (
    <ul>
      {rows.map((row, i) => {
        const target = targetLabel(row);
        const detail = detailText(row);
        const when = new Date(row.timestamp);
        const key = dayKey(when);
        const startsDay = key !== lastDay;
        lastDay = key;
        return (
          <Fragment key={row.id ?? `live-${i}`}>
            {startsDay && (
              <li className="border-y border-rule bg-sunken/70 px-4 py-1.5 text-xs font-semibold text-ink first:border-t-0">
                <time dateTime={when.toISOString().slice(0, 10)}>{dayLabel(when)}</time>
              </li>
            )}
            <li className={`${AUDIT_GRID} border-t border-rule px-4 py-2.5 text-sm first:border-t-0`}>
              <div className="min-w-0 md:order-3">
                <p className="text-ink">
                  <span className="font-medium">{actionLabel(row.actionType)}</span>
                  {target && <span className="text-muted"> · </span>}
                  {target && <span className="break-words">{target}</span>}
                </p>
                {detail && (
                  <p className="mt-0.5 truncate font-mono text-xs text-muted" title={detail}>
                    {detail}
                  </p>
                )}
              </div>

              <div className="justify-self-end md:order-4 md:justify-self-start">
                {isSuccess(row) ? (
                  <span className="text-xs text-muted">Success</span>
                ) : (
                  <Badge tone="error">Failed</Badge>
                )}
              </div>

              <time
                dateTime={row.timestamp}
                className="col-span-2 font-mono text-xs text-muted tabular md:order-1 md:col-span-1 md:pt-0.5"
              >
                {timeFormat.format(when)}
              </time>

              <p className="col-span-2 text-xs text-muted md:order-2 md:col-span-1 md:pt-0.5">
                <span className="font-medium text-ink">{actorLabel(row)}</span>
                {row.actorRole !== 'SYSTEM' && <span> · {roleLabel(row.actorRole)}</span>}
              </p>
            </li>
          </Fragment>
        );
      })}
    </ul>
  );
}
