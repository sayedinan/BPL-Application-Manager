import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Card, PageHeader } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Alert } from '@/components/ui/Alert';

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

export function DashboardPlaceholder(): JSX.Element {
  const { user } = useAuth();
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [pendingAction, setPendingAction] = useState<'start' | 'stop' | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [statsCache, setStatsCache] = useState<Record<number, AppStats>>({});
  const [statsLoading, setStatsLoading] = useState<number | null>(null);
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

  // STATUS-REDESIGN.md §2 — global topic, same reconnect-backoff-then-banner
  // pattern as LogsBox.tsx. This is additive to the 5s poll above, not a
  // replacement: WS gives instant updates, the poll is the safety net.
  const reconnectDelays = [1000, 2000, 4000, 8000, 16000, 30000];
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });

    client.onConnect = () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
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
    return () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      client.deactivate();
    };
  }, []);

  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected) {
      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];
      reconnectTimerRef.current = setTimeout(() => {
        reconnectTimerRef.current = null;
        setReconnectAttempt((a) => a + 1);
        clientRef.current?.activate();
      }, delay);
      return () => {
        if (reconnectTimerRef.current) {
          clearTimeout(reconnectTimerRef.current);
          reconnectTimerRef.current = null;
        }
      };
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
      // A start/stop can change uptime/downtime totals — drop any cached
      // stats for this app so the next expand re-fetches fresh numbers.
      setStatsCache((prev) => {
        const next = { ...prev };
        delete next[app.id];
        return next;
      });
    } catch {
      setActionError(`Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
      setPendingAction(null);
    }
  }

  function toggleDetails(appId: number) {
    if (expandedId === appId) {
      setExpandedId(null);
      return;
    }
    setExpandedId(appId);
    if (!statsCache[appId]) {
      setStatsLoading(appId);
      api
        .get<AppStats>(API.APPLICATIONS.STATS(appId))
        .then((data) => setStatsCache((prev) => ({ ...prev, [appId]: data })))
        .catch(() => {
          // leave uncached — the panel shows a fallback message below
        })
        .finally(() => setStatsLoading(null));
    }
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
            const stats = statsCache[app.id];

            return (
              <Card key={app.id} className="p-4">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="truncate font-semibold text-slate-900 dark:text-white">{app.name}</span>
                  <Badge tone={app.online ? 'online' : 'offline'}>{app.online ? 'Online' : 'Offline'}</Badge>
                </div>

                {app.startedAt && (
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Started: {new Date(app.startedAt).toLocaleString()}
                  </p>
                )}
                {runningTime && (
                  <p className="mb-2 text-xs text-slate-500 dark:text-slate-400">Running for {runningTime}</p>
                )}

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
                    {statsLoading === app.id && !stats ? (
                      <p>Loading stats…</p>
                    ) : stats ? (
                      <>
                        <p>Added: {new Date(stats.createdAt).toLocaleDateString()} (since added to BPL admin)</p>
                        <p>Total uptime: {formatDuration(stats.totalUptimeSeconds)}</p>
                        <p>Total downtime: {formatDuration(stats.totalDowntimeSeconds)}</p>
                        <p>Last ran: {stats.lastRanAt ? new Date(stats.lastRanAt).toLocaleString() : 'Never'}</p>
                      </>
                    ) : (
                      <p>Couldn't load stats.</p>
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
