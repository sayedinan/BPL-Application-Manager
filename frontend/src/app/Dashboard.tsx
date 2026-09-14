import { useEffect, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { LogsBox } from '@/app/LogsBox';
import { Card, PageHeader } from '@/components/ui/Card';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';

type Status = 'RUNNING' | 'STOPPED' | 'STARTING' | 'STOPPING' | 'ERROR';

interface ApplicationSummary {
  id: number;
  name: string;
  status: Status;
  startedAt: string | null;
}

const STATUS_TONE: Record<Status, BadgeTone> = {
  RUNNING: 'online',
  STOPPED: 'offline',
  STARTING: 'pending',
  STOPPING: 'pending',
  ERROR: 'error',
};

const STATUS_LABEL: Record<Status, string> = {
  RUNNING: 'Online',
  STOPPED: 'Offline',
  STARTING: 'Starting…',
  STOPPING: 'Stopping…',
  ERROR: 'Error',
};

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
  const [selectedAppId, setSelectedAppId] = useState<number | null>(null);
  const [, setTick] = useState(0);

  async function loadApplications() {
    try {
      const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
      setApps(list);
      setError(null);
      setSelectedAppId((prev) => (prev === null && list.length > 0 ? list[0].id : prev));
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

  async function handleStartStop(app: ApplicationSummary, action: 'start' | 'stop') {
    if (!user) return;
    setActionError(null);
    setPendingId(app.id);
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
        setActionError(`Failed to ${action} ${app.name} — check the server connection and scripts.`);
      }
    } catch {
      setActionError(`Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
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

      {error && (
        <p role="alert" className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-400">
          {error}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-400">
          {actionError}
        </p>
      )}

      {apps.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">
          {user?.role === 'SYS_ADMIN'
            ? 'No applications yet. Add one from the Applications page.'
            : 'No applications assigned. Contact your admin.'}
        </Card>
      ) : (
        <div className="mb-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {apps.map((app) => {
            const runningTime = app.status === 'RUNNING' ? formatRunningTime(app.startedAt) : null;
            const transitional = app.status === 'STARTING' || app.status === 'STOPPING';
            const online = app.status === 'RUNNING';

            return (
              <Card
                key={app.id}
                interactive
                selected={selectedAppId === app.id}
                onClick={() => setSelectedAppId(app.id)}
                className="p-4"
              >
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="truncate font-semibold text-slate-900 dark:text-white">{app.name}</span>
                  <Badge tone={STATUS_TONE[app.status]}>{STATUS_LABEL[app.status]}</Badge>
                </div>

                {app.startedAt && (
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Started: {new Date(app.startedAt).toLocaleString()}
                  </p>
                )}
                {runningTime && (
                  <p className="mb-2 text-xs text-slate-500 dark:text-slate-400">Running for {runningTime}</p>
                )}

                <div className="mt-3 flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
                  {transitional ? (
                    <Button size="sm" variant="secondary" disabled className="w-full">
                      {STATUS_LABEL[app.status]}
                    </Button>
                  ) : online ? (
                    <Button
                      size="sm"
                      variant="danger"
                      disabled={pendingId === app.id}
                      onClick={() => handleStartStop(app, 'stop')}
                      className="w-full"
                    >
                      {pendingId === app.id ? 'Stopping…' : 'Stop'}
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      variant="primary"
                      disabled={pendingId === app.id}
                      onClick={() => handleStartStop(app, 'start')}
                      className="w-full"
                    >
                      {pendingId === app.id ? 'Starting…' : 'Start'}
                    </Button>
                  )}
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {selectedAppId !== null && (
        <div>
          <h2 className="mb-2 text-lg font-semibold text-slate-900 dark:text-white">Logs</h2>
          <Card className="overflow-hidden">
            <LogsBox appId={selectedAppId} appName={apps.find((a) => a.id === selectedAppId)?.name ?? ''} role={user?.role} />
          </Card>
        </div>
      )}
    </div>
  );
}
