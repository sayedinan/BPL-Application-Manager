import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Card, PageHeader } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { LoadingBlock } from '@/components/ui/Spinner';
import { Alert } from '@/components/ui/Alert';
import { HealthSummary } from '@/health/HealthBadge';
import { classifyApp, STATE_ORDER, type AppState } from '@/lib/appState';
import { onLiveStreamState, subscribeHealth, subscribeStatus } from '@/lib/liveStream';
import type { HealthLatest } from '@/health/types';

interface ApplicationSummary {
  id: number;
  name: string;
  online: boolean;
  startedAt: string | null;
  groupName: string | null;
}


function formatRunningTime(startedAt: string | null): string | null {
  if (!startedAt) return null;
  const ms = Date.now() - new Date(startedAt).getTime();
  if (ms < 0) return null;
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  return `${h}h ${m}m ${s}s`;
}

export function DashboardPlaceholder(): JSX.Element {
  const { user } = useAuth();
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [pendingAction, setPendingAction] = useState<'start' | 'stop' | null>(null);
  const [, setTick] = useState(0);
  const [filter, setFilter] = useState<'all' | 'attention' | 'healthy' | 'maintenance'>('all');
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

  useEffect(() => {
    const clock = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(clock);
  }, []);

  // Live health for every card (HEALTH-MONITORING.md). Readings are pushed over
  // the WebSocket the moment each check finishes; the fetch gives the starting
  // values and covers gaps (every 10 s while the live connection is down,
  // every 30 s while it is up). The key only changes when the set of
  // applications does, so the 5 s list refresh doesn't restart any of this.
  const [healthById, setHealthById] = useState<Record<number, HealthLatest>>({});
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
    setActionError(null);
    setPendingId(app.id);
    setPendingAction(action);
    try {
      const path = action === 'start' ? API.APPLICATIONS.START(app.id) : API.APPLICATIONS.STOP(app.id);
      const idempotencyKey =
        typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
      const res = await fetch(`/api/v1${path}?userId=${user.id}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Idempotency-Key': idempotencyKey },
      });
      await loadApplications();
      if (!res.ok) {
        if (res.status === 504) {
          setActionError(`${action === 'start' ? 'Started' : 'Stopped'} ${app.name}, but couldn't confirm within the timeout — check its real state.`);
        } else {
          setActionError(`Failed to ${action} ${app.name} — check the server connection and scripts.`);
        }
      }
    } catch {
      setActionError(`Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
      setPendingAction(null);
    }
  }

  if (loading) {
    return (
      <LoadingBlock label="Loading applications…" className="p-8" />
    );
  }

  // What the summary tiles, the filters and the group headings are built from.
  const rows = apps.map((app) => ({ app, state: classifyApp(app, healthById[app.id]) }));
  const needsAttention = (state: AppState) => state === 'offline' || state === 'degraded';
  const counts = {
    all: rows.length,
    attention: rows.filter((r) => needsAttention(r.state)).length,
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
  const sectionKeys = [...sections.keys()].sort((a, b) => (a === '' ? 1 : b === '' ? -1 : a.localeCompare(b)));

  function groupSummary(key: string) {
    const members = rows.filter((r) => (r.app.groupName ?? '') === key);
    const hasOffline = members.some((r) => r.state === 'offline');
    const hasDegraded = members.some((r) => r.state === 'degraded');
    return {
      total: members.length,
      healthy: members.filter((r) => r.state === 'healthy').length,
      inMaintenance: members.filter((r) => r.state === 'maintenance').length,
      tone: hasOffline ? 'text-status-error' : hasDegraded ? 'text-status-pending' : 'text-status-online',
    };
  }

  const tiles: { key: 'all' | 'attention' | 'healthy' | 'maintenance'; label: string; value: number; tone: string }[] = [
    { key: 'all', label: 'Applications', value: counts.all, tone: 'text-slate-900 dark:text-white' },
    {
      key: 'attention',
      label: 'Need attention',
      value: counts.attention,
      tone: counts.attention > 0 ? 'text-status-error' : 'text-slate-900 dark:text-white',
    },
    { key: 'healthy', label: 'Healthy', value: counts.healthy, tone: 'text-status-online' },
    { key: 'maintenance', label: 'In maintenance', value: counts.maintenance, tone: 'text-status-pending' },
  ];

  function renderCard(app: ApplicationSummary): JSX.Element {
    const runningTime = app.online ? formatRunningTime(app.startedAt) : null;
    const isPending = pendingId === app.id;
    return (
      <Card key={app.id} className="overflow-hidden">
        <div className="flex items-center justify-between gap-2 border-b border-slate-200 bg-slate-50 px-4 py-2.5 dark:border-gh-border dark:bg-gh-subtle/60">
          <span className="truncate font-semibold text-slate-900 dark:text-white">{app.name}</span>
          <Badge tone={app.online ? 'online' : 'offline'}>{app.online ? 'Online' : 'Offline'}</Badge>
        </div>
        <div className="bg-slate-100 p-4 transition-theme dark:bg-gh-inset">
          {app.startedAt && (
            <p className="text-xs text-slate-500 dark:text-gh-muted">
              Started: {new Date(app.startedAt).toLocaleString()}
            </p>
          )}
          {runningTime && (
            <p className="mb-2 text-xs text-slate-500 dark:text-gh-muted">Running for {runningTime}</p>
          )}
          <HealthSummary health={healthById[app.id]} />
          <div className="mt-3 flex items-center gap-2">
            {isPending ? (
              <Button size="sm" variant="secondary" loading className="w-full">
                {pendingAction === 'start' ? 'Starting…' : 'Stopping…'}
              </Button>
            ) : app.online ? (
              <Button size="sm" variant="danger" onClick={() => handleStartStop(app, 'stop')} className="w-full">
                Stop
              </Button>
            ) : (
              <Button size="sm" variant="primary" onClick={() => handleStartStop(app, 'start')} className="w-full">
                Start
              </Button>
            )}
          </div>
          <Link
            to={`/apps/${app.id}`}
            className="mt-2 block w-full text-center text-xs font-medium text-slate-500 hover:text-slate-700 dark:text-gh-muted dark:hover:text-gh-fg"
          >
            Details →
          </Link>
        </div>
      </Card>
    );
  }

  return (
    <div className="mx-auto max-w-6xl p-6 sm:p-8">
      <PageHeader title="Dashboard" />

      {error && <Alert className="mb-4">{error}</Alert>}
      {actionError && <Alert className="mb-4">{actionError}</Alert>}
      {wsDisconnected && (
        <Alert className="mb-4">Live status updates disconnected — retrying. Falling back to periodic refresh.</Alert>
      )}

      {apps.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-gh-muted">
          {user?.role === 'SYS_ADMIN'
            ? 'No applications yet. Add one from the Applications page.'
            : 'No applications assigned. Contact your admin.'}
        </Card>
      ) : (
        <>
          <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            {tiles.map((tile) => (
              <button
                key={tile.key}
                type="button"
                aria-pressed={filter === tile.key}
                onClick={() => setFilter(filter === tile.key ? 'all' : tile.key)}
                className={`rounded-xl border bg-slate-50 px-4 py-3 text-left transition-colors dark:bg-gh-subtle/60 ${
                  filter === tile.key
                    ? 'border-brand-500 ring-1 ring-brand-500'
                    : 'border-slate-200 hover:border-slate-300 dark:border-gh-border dark:hover:border-gh-muted'
                }`}
              >
                <span className={`block text-2xl font-semibold ${tile.tone}`}>{tile.value}</span>
                <span className="text-xs text-slate-500 dark:text-gh-muted">{tile.label}</span>
              </button>
            ))}
          </div>

          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search applications"
            aria-label="Search applications"
            className="mb-6 w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg sm:max-w-sm"
          />

          {visible.length === 0 ? (
            <Card className="p-8 text-center text-sm text-slate-500 dark:text-gh-muted">
              No applications match.
            </Card>
          ) : (
            <div className="space-y-8">
              {sectionKeys.map((key) => {
                const summary = groupSummary(key);
                return (
                  <section key={key || 'other'}>
                    {hasGroups && (
                      <div className="mb-3 flex flex-wrap items-baseline gap-x-3">
                        <h2 className="text-base font-semibold text-slate-900 dark:text-white">{key || 'Other'}</h2>
                        <span className={`text-sm font-medium ${summary.tone}`}>
                          {summary.healthy}/{summary.total} healthy
                        </span>
                        {summary.inMaintenance > 0 && (
                          <span className="text-xs text-slate-500 dark:text-gh-muted">
                            · {summary.inMaintenance} in maintenance
                          </span>
                        )}
                      </div>
                    )}
                    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                      {(sections.get(key) ?? []).map((row) => renderCard(row.app))}
                    </div>
                  </section>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}
