import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, CSRF_HEADERS } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { PageHeader } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Alert } from '@/components/ui/Alert';
import { StatusGlyph } from '@/components/ui/Badge';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState } from '@/components/ui/EmptyState';
import { Input } from '@/components/ui/Field';
import { Skeleton } from '@/components/ui/Skeleton';
import { useToast } from '@/components/ui/Toast';
import { classifyApp, stateBadge, STATE_ORDER, type AppState } from '@/lib/appState';
import { appPath } from '@/lib/appRoute';
import { formatUptime } from '@/lib/duration';
import { onLiveStreamState, subscribeHealth, subscribeStatus } from '@/lib/liveStream';
import { DayAxis } from '@/health/DayStrip';
import type { HealthLatest } from '@/health/types';
import { ApplicationRow, ROW_GRID, type ApplicationSummary } from '@/app/ApplicationRow';
import { SummaryTiles, type Filter } from '@/app/SummaryTiles';

/** How many applications the attention band names before collapsing to "and N more". */
const ATTENTION_NAMED = 4;
/** After a start/stop finishes, the row's button is inert this long (ms). */
const COOLDOWN_MS = 1200;

export function DashboardPlaceholder(): JSX.Element {
  const { user } = useAuth();
  const toast = useToast();
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [pendingAction, setPendingAction] = useState<'start' | 'stop' | null>(null);
  const [cooldownId, setCooldownId] = useState<number | null>(null);
  const [confirmStop, setConfirmStop] = useState<ApplicationSummary | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [search, setSearch] = useState('');

  async function loadApplications() {
    try {
      const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
      setApps(list);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load applications.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadApplications();
    const interval = setInterval(() => void loadApplications(), 5000);
    return () => clearInterval(interval);
  }, []);

  // Live health for every row (HEALTH-MONITORING.md). Readings are pushed over
  // the WebSocket the moment each check finishes; the fetch gives the starting
  // values and covers gaps (every 10 s while the live connection is down,
  // every 30 s while it is up). The key only changes when the set of
  // applications does, so the 5 s list refresh doesn't restart any of this.
  const [healthById, setHealthById] = useState<Record<number, HealthLatest>>({});
  const [healthLoaded, setHealthLoaded] = useState(false);
  const appIdsKey = apps.map((a) => a.id).join(',');
  const healthLiveRef = useRef(false);

  const [streamUp, setStreamUp] = useState(false);

  useEffect(
    () =>
      onLiveStreamState((connected) => {
        healthLiveRef.current = connected;
        setStreamUp(connected);
      }),
    [],
  );

  useEffect(() => {
    if (!appIdsKey) return;
    const ids = appIdsKey.split(',').map(Number);
    let cancelled = false;
    let lastFetch = 0;

    async function loadHealth() {
      lastFetch = Date.now();
      const results = await Promise.all(
        ids.map((id) =>
          api
            .get<HealthLatest>(API.APPLICATIONS.HEALTH(id))
            .then((h) => [id, h] as const)
            .catch(() => null),
        ),
      );
      if (cancelled) return;
      setHealthById((prev) => {
        const next = { ...prev };
        for (const r of results) {
          if (r) next[r[0]] = r[1];
        }
        return next;
      });
      setHealthLoaded(true);
    }

    void loadHealth();
    const stops = ids.map((id) =>
      subscribeHealth(id, (health) => setHealthById((prev) => ({ ...prev, [id]: health }))),
    );
    const timer = setInterval(() => {
      const due = healthLiveRef.current ? 30000 : 10000;
      if (Date.now() - lastFetch >= due - 500) void loadHealth();
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(timer);
      stops.forEach((stop) => stop());
    };
  }, [appIdsKey]);

  // STATUS-REDESIGN.md §2: Online/Offline changes arrive instantly over the
  // tab's shared live connection (lib/liveStream.ts), one topic per application:
  // the server only lets a viewer join the topics of applications they may see.
  // This is additive to the 5 s list refresh above, which stays as the safety net.
  useEffect(() => {
    if (!appIdsKey) return;
    const stops = appIdsKey
      .split(',')
      .map(Number)
      .map((id) =>
        subscribeStatus(id, (event) =>
          setApps((prev) =>
            prev.map((a) =>
              a.id === event.applicationId
                ? { ...a, online: event.online, startedAt: event.online ? event.transitionedAt : null }
                : a,
            ),
          ),
        ),
      );
    return () => stops.forEach((stop) => stop());
  }, [appIdsKey]);

  // Short drops are normal, so only warn once the connection has been down a while.
  const [wsDisconnected, setWsDisconnected] = useState(false);
  useEffect(() => {
    if (streamUp) {
      setWsDisconnected(false);
      return;
    }
    const timer = setTimeout(() => setWsDisconnected(true), 30000);
    return () => clearTimeout(timer);
  }, [streamUp]);

  async function handleStartStop(app: ApplicationSummary, action: 'start' | 'stop') {
    if (!user) return;
    setPendingId(app.id);
    setPendingAction(action);
    try {
      const path = action === 'start' ? API.APPLICATIONS.START(app.id) : API.APPLICATIONS.STOP(app.id);
      const idempotencyKey =
        typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
      const res = await fetch(`/api/v1${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: { ...CSRF_HEADERS, 'Idempotency-Key': idempotencyKey },
      });
      await loadApplications();
      if (res.ok) {
        toast.push('success', `${action === 'start' ? 'Started' : 'Stopped'} ${app.name}.`);
      } else if (res.status === 504) {
        toast.push(
          'error',
          `${action === 'start' ? 'Started' : 'Stopped'} ${app.name}, but couldn't confirm within the timeout — check its real state.`,
        );
      } else {
        const problem = (await res.json().catch(() => null)) as { code?: string; detail?: string } | null;
        toast.push(
          'error',
          problem?.code === 'APPLICATION_BUSY' && problem.detail
            ? problem.detail
            : `Failed to ${action} ${app.name} — check the server connection and scripts.`,
        );
      }
    } catch {
      toast.push('error', `Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
      setPendingAction(null);
      // The button swaps Start <-> Stop in the same spot. Keep it inert for a moment
      // so a late second tap on the same spot doesn't immediately undo the action.
      setCooldownId(app.id);
      window.setTimeout(() => setCooldownId((cur) => (cur === app.id ? null : cur)), COOLDOWN_MS);
    }
  }

  function confirmAndStop() {
    const app = confirmStop;
    setConfirmStop(null);
    if (app) void handleStartStop(app, 'stop');
  }

  const header = (
    <PageHeader title="Dashboard" description="Status of your applications, with today's health checks by hour." />
  );

  if (loading) {
    return (
      <div className="page" aria-busy="true">
        {header}
        <p className="sr-only" role="status">
          Loading applications…
        </p>
        <div className="divide-y divide-rule rounded-xl border border-rule bg-panel">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className={`${ROW_GRID} px-4 py-4`}>
              <Skeleton className="h-5 w-40 md:order-2" />
              <Skeleton className="h-8 w-16 md:order-4" />
              <Skeleton className="col-span-2 h-5 w-full md:order-3 md:col-span-1" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // A failed first load is not an empty fleet: say so, and offer a retry.
  if (error && apps.length === 0) {
    return (
      <div className="page">
        {header}
        <EmptyState
          title="Couldn't load applications"
          description={error}
          action={
            <Button
              onClick={() => {
                setLoading(true);
                void loadApplications();
              }}
            >
              Try again
            </Button>
          }
        />
      </div>
    );
  }

  // What the filters, the attention band and the group headings are built from.
  const rows = apps.map((app) => ({ app, state: classifyApp(app, healthById[app.id]) }));
  const needsAttention = (state: AppState) => state === 'offline' || state === 'degraded';
  const attentionRows = rows
    .filter((r) => needsAttention(r.state))
    .sort((a, b) => STATE_ORDER[a.state] - STATE_ORDER[b.state] || a.app.name.localeCompare(b.app.name));
  const counts = {
    all: rows.length,
    attention: attentionRows.length,
    healthy: rows.filter((r) => r.state === 'healthy').length,
    maintenance: rows.filter((r) => r.state === 'maintenance').length,
  };
  const needle = search.trim().toLowerCase();
  const visible = rows
    .filter((r) => {
      if (needle && !r.app.name.toLowerCase().includes(needle)) return false;
      if (filter === 'attention') return needsAttention(r.state);
      if (filter === 'healthy') return r.state === 'healthy';
      if (filter === 'maintenance') return r.state === 'maintenance';
      return true;
    })
    .sort((a, b) => STATE_ORDER[a.state] - STATE_ORDER[b.state] || a.app.name.localeCompare(b.app.name));

  const hasGroups = apps.some((a) => a.groupName);
  const sections = new Map<string, typeof visible>();
  for (const row of visible) {
    const key = hasGroups ? (row.app.groupName ?? '') : '';
    sections.set(key, [...(sections.get(key) ?? []), row]);
  }
  // Worst-first: a group with something down comes before a healthy one, so a
  // failure is never buried under an alphabetically earlier group. Ungrouped last.
  const worst = (key: string) => Math.min(...(sections.get(key) ?? []).map((r) => STATE_ORDER[r.state]));
  const sectionKeys = [...sections.keys()].sort((a, b) => {
    if (a === '') return 1;
    if (b === '') return -1;
    return worst(a) - worst(b) || a.localeCompare(b);
  });

  function groupSummary(key: string) {
    const members = rows.filter((r) => (r.app.groupName ?? '') === key);
    const hasOffline = members.some((r) => r.state === 'offline');
    const hasDegraded = members.some((r) => r.state === 'degraded');
    return {
      total: members.length,
      healthy: members.filter((r) => r.state === 'healthy').length,
      inMaintenance: members.filter((r) => r.state === 'maintenance').length,
      tone: hasOffline ? 'text-danger' : hasDegraded ? 'text-warn' : 'text-ok',
    };
  }


  const stopUptime = confirmStop ? formatUptime(confirmStop.startedAt) : null;

  return (
    <div className="page">
      {header}

      <div className="space-y-3 empty:hidden">
        {error && <Alert>{error}</Alert>}
        {wsDisconnected && (
          <Alert tone="warning">Live status updates disconnected — retrying. Falling back to periodic refresh.</Alert>
        )}
      </div>

      {apps.length === 0 ? (
        <EmptyState
          title={user?.role === 'SYS_ADMIN' ? 'No applications yet' : 'No applications assigned'}
          description={
            user?.role === 'SYS_ADMIN'
              ? 'Add the first application to start, stop and monitor it from here.'
              : 'Ask an admin to assign you the applications you need.'
          }
          action={
            user?.role === 'SYS_ADMIN' ? (
              <Link
                to="/applications"
                className="inline-flex items-center rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white transition-theme hover:bg-brand-700"
              >
                Add application
              </Link>
            ) : undefined
          }
        />
      ) : (
        <>
          <SummaryTiles counts={counts} active={filter} onSelect={setFilter} />

          {counts.attention > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-1.5 rounded-xl border border-l-4 border-rule border-l-danger bg-panel px-4 py-3.5 text-sm shadow-card dark:shadow-card-dark">
              <span className="flex items-center gap-2 font-semibold text-danger">
                <StatusGlyph tone="error" />
                {counts.attention} {counts.attention === 1 ? 'needs' : 'need'} attention
              </span>
              <ul className="flex flex-wrap gap-x-4 gap-y-1">
                {attentionRows.slice(0, ATTENTION_NAMED).map(({ app, state }) => (
                  <li key={app.id} className="min-w-0 [overflow-wrap:anywhere]">
                    <Link to={appPath(app.name)} className="font-medium text-ink hover:underline">
                      {app.name}
                    </Link>{' '}
                    <span className="text-muted">({stateBadge(state, healthById[app.id]).label})</span>
                  </li>
                ))}
              </ul>
              {attentionRows.length > ATTENTION_NAMED && (
                <button
                  type="button"
                  onClick={() => setFilter('attention')}
                  className="font-medium text-accent hover:underline"
                >
                  and {attentionRows.length - ATTENTION_NAMED} more
                </button>
              )}
            </div>
          )}

          <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
            <Input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search applications"
              aria-label="Search applications"
              className="sm:max-w-xs"
            />
            <details className="text-xs text-muted sm:pt-2.5 sm:text-right">
              <summary className="w-fit cursor-pointer py-1 sm:ml-auto select-none font-medium hover:text-ink">
                How to read the hourly bars
              </summary>
              <p className="mt-2 max-w-prose sm:text-left">
                One bar per hour since midnight. A tall red bar means health checks failed that hour, a short green bar
                means every check passed, and a thin gray line means no data yet. The percentage is the share of today's
                checks that passed.
              </p>
            </details>

          </div>

          {visible.length === 0 ? (
            <EmptyState
              title="No applications match"
              description="Try a different search, or clear the filter."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    setFilter('all');
                    setSearch('');
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <div className="animate-rise space-y-8 [animation-delay:220ms]">
              {sectionKeys.map((key, index) => {
                const summary = groupSummary(key);
                const heading = key || 'Ungrouped';
                return (
                  <section key={key || 'other'} aria-label={hasGroups ? heading : 'Applications'}>
                    {hasGroups && (
                      <div className="mb-2 flex flex-wrap items-baseline gap-x-3">
                        <h2 className="text-sm font-semibold text-ink">{heading}</h2>
                        <span className={`tabular text-xs font-medium ${summary.tone}`}>
                          {summary.healthy} of {summary.total} healthy
                        </span>
                        {summary.inMaintenance > 0 && (
                          <span className="tabular text-xs text-muted">{summary.inMaintenance} in maintenance</span>
                        )}
                      </div>
                    )}
                    <div className="overflow-hidden rounded-xl border border-rule bg-panel shadow-card dark:shadow-card-dark">
                      {/* Column headers once, above the first group: repeating them per group is noise. */}
                      {index === 0 && (
                        <div
                          aria-hidden="true"
                          className={`${ROW_GRID} hidden border-b border-rule bg-sunken px-4 py-2 text-xs font-semibold uppercase tracking-wider text-muted md:grid`}
                        >
                          <span>Status</span>
                          <span>Application</span>
                          <div>
                            <span className="block">Today, by hour</span>
                            <DayAxis />
                          </div>
                          <span />
                        </div>
                      )}
                      <ul className="divide-y divide-rule">
                        {(sections.get(key) ?? []).map(({ app, state }) => (
                          <ApplicationRow
                            key={app.id}
                            app={app}
                            state={state}
                            health={healthById[app.id]}
                            healthLoaded={healthLoaded}
                            pending={pendingId === app.id ? pendingAction : null}
                            cooling={cooldownId === app.id}
                            onStart={(a) => void handleStartStop(a, 'start')}
                            onRequestStop={setConfirmStop}
                          />
                        ))}
                      </ul>
                    </div>
                  </section>
                );
              })}
            </div>
          )}
        </>
      )}

      {confirmStop && (
        <ConfirmDialog
          title={`Stop ${confirmStop.name}?`}
          message={
            <>
              {stopUptime ? `It has been up ${stopUptime}. ` : ''}
              Stopping runs its stop script and takes it offline.
            </>
          }
          confirmLabel={`Stop ${confirmStop.name}`}
          onConfirm={confirmAndStop}
          onCancel={() => setConfirmStop(null)}
        />
      )}
    </div>
  );
}
