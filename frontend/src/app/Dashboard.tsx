import { useEffect, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { LogsBox } from '@/app/LogsBox';

type Status = 'RUNNING' | 'STOPPED' | 'STARTING' | 'STOPPING' | 'ERROR';

interface ApplicationSummary {
  id: number;
  name: string;
  status: Status;
  startedAt: string | null;
}

const STATUS_COLORS: Record<Status, string> = {
  RUNNING: 'bg-green-100 text-green-800',
  STOPPED: 'bg-gray-100 text-gray-700',
  STARTING: 'bg-yellow-100 text-yellow-800',
  STOPPING: 'bg-yellow-100 text-yellow-800',
  ERROR: 'bg-red-100 text-red-800',
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
        setActionError(
          `Failed to ${action} ${app.name} — check the server connection and scripts.`
        );
      }
    } catch {
      setActionError(`Failed to ${action} ${app.name}.`);
    } finally {
      setPendingId(null);
    }
  }

  if (loading) {
    return <div className="p-8">Loading applications…</div>;
  }

  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold mb-1">Dashboard</h1>
      <p className="text-sm text-gray-600 mb-6">Signed in as {user?.username} ({user?.role})</p>
      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}
      {actionError && <p role="alert" className="text-sm text-red-600 mb-4">{actionError}</p>}
      {apps.length === 0 ? (
        <p className="text-gray-600">
          {user?.role === 'SYS_ADMIN'
            ? 'No applications yet. Add one from the Applications page.'
            : 'No applications assigned. Contact your admin.'}
        </p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-8">
          {apps.map((app) => {
            const runningTime = app.status === 'RUNNING' ? formatRunningTime(app.startedAt) : null;
            const locked = app.status === 'STARTING' || app.status === 'STOPPING';
            return (
              <div key={app.id} onClick={() => setSelectedAppId(app.id)} className={`border rounded p-4 cursor-pointer ${selectedAppId === app.id ? 'ring-2 ring-blue-500' : ''}`}>
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium">{app.name}</span>
                  <span className={`text-xs px-2 py-1 rounded ${STATUS_COLORS[app.status]}`}>{app.status}</span>
                </div>
                {app.startedAt && <p className="text-xs text-gray-500 mb-1">Started: {new Date(app.startedAt).toLocaleString()}</p>}
                {runningTime && <p className="text-xs text-gray-500 mb-2">Running for {runningTime}</p>}
                <div className="flex gap-2 mt-2" onClick={(e) => e.stopPropagation()}>
                  <button disabled={locked || app.status === 'RUNNING' || pendingId === app.id} onClick={() => handleStartStop(app, 'start')} className="text-xs px-3 py-1 bg-green-600 text-white rounded disabled:opacity-40">
                    {pendingId === app.id && app.status !== 'RUNNING' ? '…' : 'Start'}
                  </button>
                  <button disabled={locked || app.status === 'STOPPED' || pendingId === app.id} onClick={() => handleStartStop(app, 'stop')} className="text-xs px-3 py-1 bg-red-600 text-white rounded disabled:opacity-40">
                    {pendingId === app.id && app.status !== 'STOPPED' ? '…' : 'Stop'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
      {selectedAppId !== null && (
        <div>
          <h2 className="text-lg font-medium mb-2">Logs</h2>
          <LogsBox appId={selectedAppId} role={user?.role} />
        </div>
      )}
    </div>
  );
}
