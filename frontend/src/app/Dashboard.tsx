import { useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Card, PageHeader } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Alert } from '@/components/ui/Alert';
import { UptimeDonut } from '@/components/charts/UptimeDonut';
import { UptimeBarChart } from '@/components/charts/UptimeBarChart';

interface ApplicationSummary {
  id: number;
  name: string;
  online: boolean;
  startedAt: string | null;
}

interface StatusEvent {
  applicationId: number;
  online: boolean;
  transitionedAt: string;
}

interface AppStats {
  createdAt: string;
  totalUptimeSeconds: number;
  totalDowntimeSeconds: number;
  currentlyOnline: boolean;
  currentStreakStartedAt: string | null;
  lastRanAt: string | null;
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

function formatDuration(totalSeconds: number): string {
  const d = Math.floor(totalSeconds / 86400);
  const h = Math.floor((totalSeconds % 86400) / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function KpiCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <Card className="p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">{label}</p>
      <p className="mt-1 text-2xl font-bold tabular-nums text-slate-900 dark:text-white">{value}</p>
      {sub && <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{sub}</p>}
    </Card>
  );
}

export function DashboardPlaceholder(): JSX.Element {
  const { user } = useAuth();
  const canViewAudit = user?.role === 'SYS_ADMIN' || user?.role === 'ADMIN';
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [pendingAction, setPendingAction] = useState<'start' | 'stop' | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [statsMap, setStatsMap] = useState<Record<number, AppStats>>({});
  const [actionsToday, setActionsToday] = useState<number | null>(null);
  const [, setTick] = useState(0);
  const clientRef = useRef<Client | null>(null);

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

  // Powers the KPI strip and the per-app donuts/bar chart. Fetched for
  // every app in parallel whenever the app list changes shape, and on
  // a slower independent interval — stats don't need 5s freshness like
  // online/offline status does, so this stays light on the backend.
  async function loadAllStats(appList: ApplicationSummary[]) {
    const entries = await Promise.all(
      appList.map(async (app) => {
        try {
          const s = await api.get<AppStats>(API.APPLICATIONS.STATS(app.id));
          return [app.id, s] as const;
        } catch {
          return null;
        }
      })
    );
    setStatsMap((prev) => {
      const next = { ...prev };
      for (const entry of entries) {
        if (entry) next[entry[0]] = entry[1];
      }
      return next;
    });
  }

  const appIdsKey = apps.map((a) => a.id).join(',');
  useEffect(() => {
    if (apps.length > 0) void loadAllStats(apps);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appIdsKey]);

  useEffect(() => {
    if (apps.length === 0) return;
    const interval = setInterval(() => void loadAllStats(apps), 20000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appIdsKey]);

  useEffect(() => {
    if (!canViewAudit) return;
    async function loadActionsToday() {
      try {
        const startOfDay = new Date();
        startOfDay.setHours(0, 0, 0, 0);
        const res = await api.get<{ total: number }>(
          `${API.AUDIT_LOGS}?page=0&size=1&from=${startOfDay.toISOString()}`
        );
        setActionsToday(res.total);
      } catch {
        setActionsToday(null);
      }
    }
    void loadActionsToday();
    const interval = setInterval(() => void loadActionsToday(), 30000);
    return () => clearInterval(interval);
  }, [canViewAudit]);

  // STATUS-REDESIGN.md §2 — global topic, same reconnect-backoff-then-banner
  // pattern as LogsBox.tsx. This is additive to the 5s poll above, not a
  // replacement: WS gives instant updates, the poll is the safety net.
  const reconnectDelays = [1000, 2000, 4000, 8000, 16000, 30000];
  const [reconnectAttempt, setReconnectAttempt] = useState(0);

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });

    client.onConnect = () => {
      setReconnectAttempt(0);
      client.subscribe('/topic/application-status', (msg) => {
        try {
          const d = JSON.parse(msg.body) as StatusEvent;
          setApps((prev) =>
            prev.map((a) =>
              a.id === d.applicationId
                ? { ...a, online: d.online, startedAt: d.online ? d.transitionedAt : null }
                : a
            )
          );
        } catch {
          // malformed frame — ignore, next poll tick will reconcile
        }
      });
    };

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, []);

  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected) {
      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];
      const timer = setTimeout(() => { setReconnectAttempt((a) => a + 1); clientRef.current?.activate(); }, delay);
      return () => clearTimeout(timer);
    }
  }, [reconnectAttempt]);

  const wsDisconnected = reconnectAttempt >= 10;

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
      void loadAllStats(apps);
    } catch {
      setActionError(`Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
      setPendingAction(null);
    }
  }

  const kpis = useMemo(() => {
    const onlineCount = apps.filter((a) => a.online).length;
    const statsValues = Object.values(statsMap);
    const totalUp = statsValues.reduce((s, v) => s + v.totalUptimeSeconds, 0);
    const totalDown = statsValues.reduce((s, v) => s + v.totalDowntimeSeconds, 0);
    const avgUptimePct = totalUp + totalDown > 0 ? Math.round((totalUp / (totalUp + totalDown)) * 100) : null;

    let streakLeader: { name: string; seconds: number } | null = null;
    for (const app of apps) {
      const s = statsMap[app.id];
      if (s?.currentlyOnline && s.currentStreakStartedAt) {
        const seconds = (Date.now() - new Date(s.currentStreakStartedAt).getTime()) / 1000;
        if (!streakLeader || seconds > streakLeader.seconds) {
          streakLeader = { name: app.name, seconds };
        }
      }
    }

    return { onlineCount, avgUptimePct, streakLeader };
  }, [apps, statsMap]);

  const barData = useMemo(
    () =>
      apps.map((app) => {
        const s = statsMap[app.id];
        const total = s ? s.totalUptimeSeconds + s.totalDowntimeSeconds : 0;
        const uptimePct = s && total > 0 ? Math.round((s.totalUptimeSeconds / total) * 100) : 0;
        return { name: app.name, uptimePct };
      }),
    [apps, statsMap]
  );

  function toggleDetails(appId: number) {
    setExpandedId((prev) => (prev === appId ? null : appId));
  }

  if (loading) {
    return (
      <div className="p-8 text-sm text-slate-500 dark:text-slate-400">Loading applications…</div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl p-6 sm:p-8">
      <PageHeader
        title="Dashboard"
        description={`Signed in as ${user?.username} (${user?.role})`}
      />

      {error && <Alert className="mb-4">{error}</Alert>}
      {actionError && <Alert className="mb-4">{actionError}</Alert>}
      {wsDisconnected && (
        <Alert className="mb-4">Live status updates disconnected — retrying. Falling back to periodic refresh.</Alert>
      )}

      {apps.length > 0 && (
        <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
          <KpiCard label="Apps Online" value={`${kpis.onlineCount} / ${apps.length}`} />
          <KpiCard
            label="Avg Uptime"
            value={kpis.avgUptimePct !== null ? `${kpis.avgUptimePct}%` : '—'}
            sub="lifetime, all apps"
          />
          {canViewAudit && (
            <KpiCard label="Actions Today" value={actionsToday !== null ? String(actionsToday) : '—'} />
          )}
          <KpiCard
            label="Longest Streak"
            value={kpis.streakLeader ? formatDuration(Math.floor(kpis.streakLeader.seconds)) : '—'}
            sub={kpis.streakLeader?.name}
          />
        </div>
      )}

      {apps.length > 1 && barData.some((b) => b.uptimePct > 0) && (
        <Card className="mb-6 p-4">
          <h2 className="mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">Uptime by application</h2>
          <UptimeBarChart data={barData} />
        </Card>
      )}

      {apps.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">
          {user?.role === 'SYS_ADMIN'
            ? 'No applications yet. Add one from the Applications page.'
            : 'No applications assigned. Contact your admin.'}
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {apps.map((app) => {
            const runningTime = app.online ? formatRunningTime(app.startedAt) : null;
            const isPending = pendingId === app.id;
            const isExpanded = expandedId === app.id;
            const stats = statsMap[app.id];

            return (
              <Card key={app.id} className="p-4">
                <div className="mb-2 flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="mb-1 flex items-center gap-2">
                      <span className="truncate font-semibold text-slate-900 dark:text-white">{app.name}</span>
                      <Badge tone={app.online ? 'online' : 'offline'}>{app.online ? 'Online' : 'Offline'}</Badge>
                    </div>
                    {app.startedAt && (
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        Started: {new Date(app.startedAt).toLocaleString()}
                      </p>
                    )}
                    {runningTime && (
                      <p className="text-xs text-slate-500 dark:text-slate-400">Running for {runningTime}</p>
                    )}
                  </div>
                  {stats && (
                    <UptimeDonut uptimeSeconds={stats.totalUptimeSeconds} downtimeSeconds={stats.totalDowntimeSeconds} size={56} />
                  )}
                </div>

                <div className="mt-3 flex items-center gap-2">
                  {isPending ? (
                    <Button size="sm" variant="secondary" disabled className="w-full">
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

                <button
                  type="button"
                  onClick={() => toggleDetails(app.id)}
                  className="mt-2 w-full text-center text-xs font-medium text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
                >
                  {isExpanded ? 'Hide details ▲' : 'Details ▼'}
                </button>

                {isExpanded && (
                  <div className="mt-2 space-y-1 rounded-md bg-slate-50 p-3 text-xs text-slate-600 dark:bg-slate-800/50 dark:text-slate-300">
                    {!stats ? (
                      <p>Loading stats…</p>
                    ) : (
                      <>
                        <p>Added: {new Date(stats.createdAt).toLocaleDateString()} (since added to BPL admin)</p>
                        <p>Total uptime: {formatDuration(stats.totalUptimeSeconds)}</p>
                        <p>Total downtime: {formatDuration(stats.totalDowntimeSeconds)}</p>
                        <p>Last ran: {stats.lastRanAt ? new Date(stats.lastRanAt).toLocaleString() : 'Never'}</p>
                      </>
                    )}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
