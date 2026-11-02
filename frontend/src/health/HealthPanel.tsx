import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { LoadingBlock } from '@/components/ui/Spinner';
import { HealthBadge, timeAgo } from './HealthBadge';
import { Sparkline } from './Sparkline';
import { RangeButtons } from './RangeButtons';
import type { HealthHistory, HealthStatus } from './types';
import type { HealthLiveData, HealthSample } from './useHealthLive';

type RangeKey = 'live' | 'day' | 'week' | 'month';
const RANGES: { key: RangeKey; label: string; hours: number }[] = [
  { key: 'live', label: 'Live', hours: 0 },
  { key: 'day', label: '1 day', hours: 24 },
  { key: 'week', label: '7 days', hours: 168 },
  { key: 'month', label: '1 month', hours: 720 },
];
// Long ranges change slowly and cost the server more to compute, so they
// refresh less often.
function historyRefreshMs(hours: number): number {
  if (hours <= 1) return 30000;
  if (hours <= 24) return 60000;
  return 300000;
}

function statusTone(status: HealthStatus): BadgeTone {
  if (status === 'UP') return 'online';
  if (status === 'DEGRADED') return 'pending';
  if (status === 'DOWN') return 'error';
  return 'offline';
}

function formatDuration(totalSeconds: number): string {
  const t = Math.max(0, Math.floor(totalSeconds));
  const d = Math.floor(t / 86400);
  const h = Math.floor((t % 86400) / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  return `${d}d ${h}h ${m}m ${s}s`;
}

function formatWhen(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

// Links come from the monitored application, so only plain web links are
// made clickable.
function safeHref(url: string | undefined): string | null {
  return url && /^https?:\/\//i.test(url) ? url : null;
}

/** Card with a gray header strip and a darker inset body, like the Dashboard and Logs cards. */
export function Section({
  title,
  aside,
  children,
}: {
  title: string;
  /** Small text at the right end of the header strip. */
  aside?: ReactNode;
  children: ReactNode;
}): JSX.Element {
  return (
    <section className="overflow-hidden rounded-2xl border border-slate-200 dark:border-gh-border">
      <div className="flex items-center justify-between gap-3 border-b border-slate-200 bg-slate-50 px-4 py-2.5 dark:border-gh-border dark:bg-gh-subtle/60">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-gh-muted">{title}</h3>
        {aside && <span className="text-xs text-slate-500 dark:text-gh-muted">{aside}</span>}
      </div>
      <div className="bg-slate-100 p-4 transition-theme dark:bg-gh-inset">{children}</div>
    </section>
  );
}

export function Row({ label, children }: { label: string; children: ReactNode }): JSX.Element {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm first:pt-0 last:pb-0">
      <dt className="shrink-0 text-slate-500 dark:text-gh-muted">{label}</dt>
      <dd className="min-w-0 break-words text-right font-medium text-slate-900 dark:text-gh-fg">{children}</dd>
    </div>
  );
}

/** Green pulsing dot while readings arrive live; gray "Polling" when the connection is down. */
function LiveIndicator({ live }: { live: boolean }): JSX.Element {
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 dark:text-gh-muted"
      title={
        live
          ? 'Readings arrive the moment each check finishes'
          : 'Live connection is down; refreshing every 10 seconds'
      }
    >
      <span className={`h-2 w-2 rounded-full ${live ? 'animate-pulse bg-status-online' : 'bg-slate-400'}`} />
      {live ? 'LIVE' : 'Polling'}
    </span>
  );
}

function Gauge({
  label,
  percent,
  detail,
  toggle,
}: {
  label: string;
  percent: number | null | undefined;
  detail?: string;
  /** Optional button shown right after the label (opens the history graph). */
  toggle?: ReactNode;
}): JSX.Element | null {
  if (percent == null) return null;
  const color = percent >= 90 ? 'bg-status-error' : percent >= 70 ? 'bg-status-pending' : 'bg-status-online';
  const width = Math.min(100, Math.max(0, percent));
  return (
    <div>
      <div className="mb-1 flex justify-between gap-2 text-xs">
        <span className="inline-flex items-center gap-1 font-medium text-slate-700 dark:text-gh-fgSoft">
          {label}
          {toggle}
        </span>
        <span className="text-slate-500 dark:text-gh-muted">
          {detail ? `${detail} · ` : ''}
          {percent.toFixed(1)}%
        </span>
      </div>
      <div
        className="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-gh-hover"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(width)}
      >
        <div className={`h-full rounded-full ${color}`} style={{ width: `${width}%` }} />
      </div>
    </div>
  );
}

/**
 * One line chart of a metric over the selected range, drawn from the history
 * buckets. Red marks show buckets that had failed checks. Returns nothing when
 * the application reported no value for the metric in this range, so an
 * application without (say) a disk figure shows no empty chart for it.
 */
function HistoryChart({
  label,
  points,
  pick,
  unit,
  fixedMax,
  decimals = 0,
}: {
  label: string;
  points: HealthHistory['points'];
  pick: (point: HealthHistory['points'][number]) => number | null | undefined;
  unit: string;
  /** Top of the scale; leave out to scale to the largest value (use 100 for percentages). */
  fixedMax?: number;
  decimals?: number;
}): JSX.Element | null {
  const values = points.map((p) => pick(p) ?? null);
  const present = values.filter((v): v is number => v !== null);
  if (points.length < 2 || present.length === 0) return null;

  const peak = Math.max(...present);
  const max = fixedMax ?? Math.max(1, peak);
  const width = 300;
  const height = 60;
  const x = (i: number) => (i / (points.length - 1)) * width;
  const y = (v: number) => height - 4 - (Math.min(v, max) / max) * (height - 8);

  // A bucket without a value leaves a gap in the line instead of a drop to zero.
  let path = '';
  let penDown = false;
  values.forEach((v, i) => {
    if (v === null) {
      penDown = false;
      return;
    }
    path += `${penDown ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)} `;
    penDown = true;
  });
  const latest = [...present].pop() as number;

  return (
    <div>
      <div className="mb-1 flex justify-between gap-2 text-xs">
        <span className="font-medium text-slate-700 dark:text-gh-fgSoft">{label}</span>
        <span className="text-slate-500 dark:text-gh-muted">
          latest {latest.toFixed(decimals)}
          {unit} · peak {peak.toFixed(decimals)}
          {unit}
        </span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        className="h-16 w-full rounded bg-slate-200/60 dark:bg-gh-hover/40"
        role="img"
        aria-label={`${label} over the selected period`}
      >
        {points.map((p, i) =>
          p.failedChecks > 0 ? (
            <line
              key={p.t}
              x1={x(i)}
              x2={x(i)}
              y1={0}
              y2={height}
              className="stroke-status-error"
              strokeWidth={2}
              opacity={0.5}
              vectorEffect="non-scaling-stroke"
            />
          ) : null,
        )}
        <path
          d={path}
          fill="none"
          className="stroke-brand-500"
          strokeWidth={1.5}
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  );
}

type MetricKey = 'response' | 'cpu' | 'memory' | 'disk';
const METRICS: Record<
  MetricKey,
  {
    label: string;
    unit: string;
    decimals: number;
    fixedMax?: number;
    pick: (p: HealthHistory['points'][number]) => number | null | undefined;
    live: (s: HealthSample) => number | null;
  }
> = {
  response: { label: 'Response time', unit: ' ms', decimals: 0, pick: (p) => p.avgResponseMs, live: (s) => s.responseMs },
  cpu: { label: 'CPU', unit: '%', decimals: 1, fixedMax: 100, pick: (p) => p.avgCpuPercent, live: (s) => s.cpu },
  memory: { label: 'Memory', unit: '%', decimals: 1, fixedMax: 100, pick: (p) => p.avgMemoryPercent, live: (s) => s.memory },
  disk: { label: 'Disk', unit: '%', decimals: 1, fixedMax: 100, pick: (p) => p.avgDiskPercent, live: (s) => s.disk },
};

/** Small chevron that opens or closes a metric's history graph. */
function HistoryToggle({
  open,
  onClick,
  label,
}: {
  open: boolean;
  onClick: () => void;
  label: string;
}): JSX.Element {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-expanded={open}
      aria-label={`${open ? 'Hide' : 'Show'} ${label} history`}
      title={`${open ? 'Hide' : 'Show'} history`}
      className="inline-flex h-7 w-7 items-center justify-center rounded text-slate-500 hover:bg-slate-200 dark:text-gh-muted dark:hover:bg-gh-hover"
    >
      <svg
        viewBox="0 0 20 20"
        fill="currentColor"
        className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`}
        aria-hidden="true"
      >
        <path
          fillRule="evenodd"
          d="M5.23 7.21a.75.75 0 011.06.02L10 11.17l3.71-3.94a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"
          clipRule="evenodd"
        />
      </svg>
    </button>
  );
}

/**
 * The history graph of one metric, with its own range selector (1 day by
 * default). It only exists while opened, so it only loads data then.
 */
function MetricHistory({
  applicationId,
  metric,
  samples,
}: {
  applicationId: number;
  metric: MetricKey;
  samples: HealthSample[];
}): JSX.Element {
  const m = METRICS[metric];
  const [range, setRange] = useState<RangeKey>('day');
  const hours = RANGES.find((r) => r.key === range)?.hours ?? 24;
  const [history, setHistory] = useState<HealthHistory | null>(null);

  useEffect(() => {
    if (range === 'live') return;
    let cancelled = false;
    const load = async () => {
      try {
        const h = await api.get<HealthHistory>(API.APPLICATIONS.HEALTH_HISTORY(applicationId, hours));
        if (!cancelled) setHistory(h);
      } catch {
        // keep the previous graph; connection problems show in the live part
      }
    };
    void load();
    const timer = setInterval(() => void load(), historyRefreshMs(hours));
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [applicationId, hours, range]);

  return (
    <div className="space-y-3">
      <RangeButtons options={RANGES} value={range} onChange={setRange} />
      {range === 'live' ? (
        samples.length >= 2 ? (
          <>
            <Sparkline
              label={m.label}
              values={samples.map(m.live)}
              unit={m.unit}
              fixedMax={m.fixedMax}
              decimals={m.decimals}
            />
            <p className="text-xs text-slate-500 dark:text-gh-muted">
              Last {samples.length} checks, since you opened this page
            </p>
          </>
        ) : (
          <p className="text-xs text-slate-500 dark:text-gh-muted">Collecting readings… graph appears after two checks.</p>
        )
      ) : history && history.hours === hours && history.points.length >= 2 ? (
        <>
          <HistoryChart
            label={m.label}
            points={history.points}
            pick={m.pick}
            unit={m.unit}
            fixedMax={m.fixedMax}
            decimals={m.decimals}
          />
          <p className="text-xs text-slate-500 dark:text-gh-muted">
            {formatWhen(history.points[0].t)} to now · red marks show failed checks
          </p>
        </>
      ) : history && history.hours === hours ? (
        <p className="text-xs text-slate-500 dark:text-gh-muted">Not enough data yet.</p>
      ) : (
        <LoadingBlock label="Loading history…" className="py-4" />
      )}
    </div>
  );
}

/**
 * All the health information for one application, as a stack of cards.
 * The live readings are handed in (the page owns them, because its header
 * needs them too); the long-range history chart is loaded here.
 */
export function HealthPanel({
  applicationId,
  data,
}: {
  applicationId: number;
  data: HealthLiveData;
}): JSX.Element {
  // The Availability row always covers the last day.
  const hours = 24;
  const [open, setOpen] = useState<Record<MetricKey, boolean>>({
    response: false,
    cpu: false,
    memory: false,
    disk: false,
  });
  const toggle = (k: MetricKey) => setOpen((o) => ({ ...o, [k]: !o[k] }));
  const [history, setHistory] = useState<HealthHistory | null>(null);
  const [, setTick] = useState(0);
  const { latest, samples, live, loading, error } = data;

  // The long-range chart is not pushed: its points cover minutes to hours,
  // so a refresh every 30 s is plenty.
  const loadHistory = useCallback(async () => {
    try {
      setHistory(await api.get<HealthHistory>(API.APPLICATIONS.HEALTH_HISTORY(applicationId, hours)));
    } catch {
      // keep the previous chart; connection problems show in the live part
    }
  }, [applicationId, hours]);

  useEffect(() => {
    void loadHistory();
    const timer = setInterval(() => void loadHistory(), historyRefreshMs(hours));
    return () => clearInterval(timer);
  }, [loadHistory, hours]);

  // Keeps "checked 8s ago" and the uptime counter moving between readings.
  useEffect(() => {
    const clock = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(clock);
  }, []);

  const snap = latest?.snapshot ?? null;
  const resources = snap?.resources;
  const traffic = snap?.traffic;
  const errorRate =
    traffic?.requestCount && traffic.errorCount !== undefined
      ? (traffic.errorCount / traffic.requestCount) * 100
      : null;
  const startedAtMs = snap?.startedAt ? new Date(snap.startedAt).getTime() : null;
  const uptimeSeconds = startedAtMs !== null ? Math.max(0, (Date.now() - startedAtMs) / 1000) : null;
  const days = latest?.sslDaysRemaining ?? null;
  const certTone: BadgeTone = days === null ? 'neutral' : days < 0 || days <= 14 ? 'error' : days <= 30 ? 'pending' : 'online';

  return (
    <div className="space-y-4">
      {latest?.monitored && (
        <div className="flex flex-wrap items-center gap-3">
          <HealthBadge health={latest} />
          <LiveIndicator live={live} />
          <span className="text-xs text-slate-500 dark:text-gh-muted">
            Last checked {latest.checkedAt ? timeAgo(latest.checkedAt) : '—'}
            {latest.pollIntervalSeconds ? ` · checks about every ${latest.pollIntervalSeconds}s` : ''}
          </span>
        </div>
      )}
      {loading ? (
        <LoadingBlock label="Loading health…" className="py-8" />
      ) : error ? (
        <Alert>{error}</Alert>
      ) : !latest || !latest.monitored ? (
        <Section title="Health">
          <p className="py-4 text-center text-sm text-slate-500 dark:text-gh-muted">
            Health monitoring is not set up for this application. A Sys.Admin can set it up from the
            Applications page.
          </p>
        </Section>
      ) : (
        <>
          {latest.error && <Alert tone="warning">Last check problem: {latest.error}</Alert>}

          <Section title="Vital signs">
            <dl>
              <Row label="Reachable">{latest.reachable ? 'Yes' : 'No'}</Row>
              {latest.responseMs != null && (
                <>
                  <Row label="Response time">
                    <span className="inline-flex items-center gap-1">
                      {latest.responseMs} ms
                      <HistoryToggle open={open.response} onClick={() => toggle('response')} label="response time" />
                    </span>
                  </Row>
                  {open.response && (
                    <div className="pb-2 pt-1">
                      <MetricHistory applicationId={applicationId} metric="response" samples={samples} />
                    </div>
                  )}
                </>
              )}
              {uptimeSeconds !== null && <Row label="Running for">{formatDuration(uptimeSeconds)}</Row>}
              {history?.availabilityPercent != null && (
                <Row label="Availability (1 day)">
                  {history.availabilityPercent.toFixed(1)}% of checks passed ({history.failedChecks} of{' '}
                  {history.checks} failed)
                </Row>
              )}
            </dl>
          </Section>

          {(resources?.cpu || resources?.memory || resources?.disk) && (
            <Section title="Resources">
              <div className="space-y-4">
                <div>
                  <Gauge
                    label="CPU"
                    percent={resources?.cpu?.systemPercent ?? resources?.cpu?.processPercent}
                    toggle={<HistoryToggle open={open.cpu} onClick={() => toggle('cpu')} label="CPU" />}
                  />
                  {open.cpu && (
                    <div className="pt-3">
                      <MetricHistory applicationId={applicationId} metric="cpu" samples={samples} />
                    </div>
                  )}
                </div>
                <div>
                  <Gauge
                    label="Memory"
                    percent={resources?.memory?.usedPercent}
                    detail={
                      resources?.memory?.usedMb !== undefined && resources.memory.limitMb !== undefined
                        ? `${Math.round(resources.memory.usedMb)} of ${Math.round(resources.memory.limitMb)} MB`
                        : undefined
                    }
                    toggle={<HistoryToggle open={open.memory} onClick={() => toggle('memory')} label="memory" />}
                  />
                  {open.memory && (
                    <div className="pt-3">
                      <MetricHistory applicationId={applicationId} metric="memory" samples={samples} />
                    </div>
                  )}
                </div>
                <div>
                  <Gauge
                    label="Disk"
                    percent={resources?.disk?.usedPercent}
                    detail={
                      resources?.disk?.freeGb !== undefined
                        ? `${resources.disk.freeGb.toFixed(1)} GB free${resources.disk.path ? ` on ${resources.disk.path}` : ''}${
                            resources.disk.minFreeGb !== undefined ? ` (minimum ${resources.disk.minFreeGb} GB)` : ''
                          }`
                        : undefined
                    }
                    toggle={<HistoryToggle open={open.disk} onClick={() => toggle('disk')} label="disk" />}
                  />
                  {open.disk && (
                    <div className="pt-3">
                      <MetricHistory applicationId={applicationId} metric="disk" samples={samples} />
                    </div>
                  )}
                </div>
              </div>
            </Section>
          )}

          {snap?.checks && snap.checks.length > 0 && (
            <Section title="Connected systems">
              <ul className="divide-y divide-slate-200 dark:divide-gh-border">
                {snap.checks.map((c) => (
                  <li key={c.name} className="flex items-center justify-between gap-3 py-2 text-sm first:pt-0 last:pb-0">
                    <div className="min-w-0">
                      <p className="truncate font-medium text-slate-900 dark:text-gh-fg">{c.name}</p>
                      {c.message && <p className="text-xs text-slate-500 dark:text-gh-muted">{c.message}</p>}
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      {c.latencyMs !== undefined && (
                        <span className="text-xs text-slate-500 dark:text-gh-muted">{Math.round(c.latencyMs)} ms</span>
                      )}
                      <Badge tone={statusTone(c.status)}>{c.status}</Badge>
                    </div>
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {traffic && (
            <Section title="Traffic">
              <dl>
                {traffic.requestCount !== undefined && (
                  <Row label="Requests">
                    {traffic.requestCount.toLocaleString()}
                    {traffic.windowMinutes ? ` in the last ${traffic.windowMinutes} min` : ''}
                  </Row>
                )}
                {traffic.errorCount !== undefined && <Row label="Errors">{traffic.errorCount.toLocaleString()}</Row>}
                {errorRate !== null && <Row label="Error rate">{errorRate.toFixed(1)}%</Row>}
                {traffic.avgLatencyMs !== undefined && (
                  <Row label="Average latency">{Math.round(traffic.avgLatencyMs)} ms</Row>
                )}
              </dl>
            </Section>
          )}

          {latest.sslNotAfter && (
            <Section title="Security certificate">
              <dl>
                <Row label="Expires">{formatWhen(latest.sslNotAfter)}</Row>
                {days !== null && (
                  <Row label="Time left">
                    <Badge tone={certTone}>{days < 0 ? 'Expired' : `${days} days`}</Badge>
                  </Row>
                )}
              </dl>
            </Section>
          )}

          {snap?.app && (
            <Section title="About this application">
              <dl>
                {snap.app.description && <Row label="Description">{snap.app.description}</Row>}
                {snap.app.environment && <Row label="Environment">{snap.app.environment}</Row>}
                {snap.app.version && <Row label="Version">{snap.app.version}</Row>}
                {snap.build?.deployedAt && <Row label="Last deployed">{formatWhen(snap.build.deployedAt)}</Row>}
                {snap.app.owner && <Row label="Owner">{snap.app.owner}</Row>}
                {snap.app.contact && <Row label="Contact">{snap.app.contact}</Row>}
                {(['app', 'docs', 'runbook'] as const).map((key) => {
                  const href = safeHref(snap.app?.links?.[key]);
                  return href ? (
                    <Row key={key} label={key === 'app' ? 'Application' : key === 'docs' ? 'Documentation' : 'Runbook'}>
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-brand-600 hover:underline dark:text-brand-400"
                      >
                        Open
                      </a>
                    </Row>
                  ) : null;
                })}
              </dl>
            </Section>
          )}

          <details className="overflow-hidden rounded-2xl border border-slate-200 text-sm dark:border-gh-border">
            <summary className="cursor-pointer bg-slate-50 px-4 py-2.5 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:bg-gh-subtle/60 dark:text-gh-muted">
              Technical details
            </summary>
            <dl className="border-t border-slate-200 bg-slate-100 p-4 dark:border-gh-border dark:bg-gh-inset">
              <Row label="HTTP status">{latest.httpStatus ?? '—'}</Row>
              <Row label="Failed checks in a row">{latest.consecutiveFailures ?? 0}</Row>
              <Row label="Reported at">{formatWhen(snap?.timestamp)}</Row>
              <Row label="Contract version">{snap?.schemaVersion ?? '—'}</Row>
              {snap?.build?.commit && <Row label="Commit">{snap.build.commit}</Row>}
              {snap?.build?.branch && <Row label="Branch">{snap.build.branch}</Row>}
            </dl>
          </details>
        </>
      )}
    </div>
  );
}
