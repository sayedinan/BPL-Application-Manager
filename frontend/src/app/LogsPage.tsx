import { useEffect, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { LogsBox } from '@/app/LogsBox';
import { Card, PageHeader } from '@/components/ui/Card';
import { Alert } from '@/components/ui/Alert';

interface ApplicationSummary {
  id: number;
  name: string;
}

export function LogsPage(): JSX.Element {
  const { user } = useAuth();
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [selectedAppId, setSelectedAppId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
        if (cancelled) return;
        setApps(list);
        setError(null);
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Failed to load applications.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => { cancelled = true; };
  }, []);

  const selectedApp = apps.find((a) => a.id === selectedAppId);

  return (
    <div className="mx-auto max-w-6xl p-6 sm:p-8">
      <PageHeader title="Logs" description="Application and audit log history" />

      {error && <Alert className="mb-4">{error}</Alert>}

      {loading ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">Loading applications…</p>
      ) : apps.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">
          No applications yet.
        </Card>
      ) : (
        <>
          <div className="mb-4">
            <select
              value={selectedAppId ?? ''}
              onChange={(e) => setSelectedAppId(e.target.value === '' ? null : Number(e.target.value))}
              className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 transition-theme focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 dark:border-slate-700 dark:bg-surface-dark dark:text-slate-200"
            >
              <option value="">All Applications</option>
              {apps.map((app) => (
                <option key={app.id} value={app.id}>{app.name}</option>
              ))}
            </select>
          </div>

          <Card className="overflow-hidden">
            <LogsBox appId={selectedAppId} appName={selectedApp?.name ?? 'All Applications'} role={user?.role} />
          </Card>
        </>
      )}
    </div>
  );
}
