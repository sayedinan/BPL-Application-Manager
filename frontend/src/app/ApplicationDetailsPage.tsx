import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Badge } from '@/components/ui/Badge';
import { Card, PageHeader } from '@/components/ui/Card';
import { LoadingBlock } from '@/components/ui/Spinner';
import { HealthPanel, Row, Section } from '@/health/HealthPanel';

interface ApplicationSummary {
  id: number;
  name: string;
  online: boolean;
  startedAt: string | null;
}

interface AppStats {
  createdAt: string;
  totalUptimeSeconds: number;
  totalDowntimeSeconds: number;
  currentlyOnline: boolean;
  currentStreakStartedAt: string | null;
  lastRanAt: string | null;
  lastWentOnlineAt: string | null;
  lastWentOfflineAt: string | null;
  recentTransitions: { online: boolean; at: string }[];
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

function formatWhen(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

function BackLink(): JSX.Element {
  return (
    <Link
      to="/"
      className="mb-4 inline-block text-sm font-medium text-slate-500 hover:text-slate-800 dark:text-gh-muted dark:hover:text-gh-fg"
    >
      ← Dashboard
    </Link>
  );
}

/**
 * One page per application, reached from its Dashboard card: Online/Offline
 * state and uptime history, then everything the health monitoring knows.
 *
 * There is no page to create or delete. The page is the route /apps/:id, and
 * the application list decides whether it exists: a new application gets a
 * page the moment it is in the list, and once it is deleted (or the viewer
 * is not assigned to it) the page shows "not found" instead.
 */
export function ApplicationDetailsPage(): JSX.Element {
  const { id } = useParams();
  const appId = Number(id);
  const validId = Number.isInteger(appId) && appId > 0;

  // undefined = still loading, null = not in the list (deleted or no access)
  const [app, setApp] = useState<ApplicationSummary | null | undefined>(undefined);
  const [stats, setStats] = useState<AppStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0);

  const loadApp = useCallback(async () => {
    try {
      const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
      setApp(list.find((a) => a.id === appId) ?? null);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load the application.');
    }
  }, [appId]);

  useEffect(() => {
    if (!validId) return;
    void loadApp();
    const timer = setInterval(() => void loadApp(), 5000);
    return () => clearInterval(timer);
  }, [validId, loadApp]);

  // Uptime history. Only asked for while the application is in the list: the
  // stats endpoint fails for an application that no longer exists.
  const online = app ? app.online : null;
  useEffect(() => {
    if (!validId || online === null) return;
    let cancelled = false;
    const loadStats = () =>
      api
        .get<AppStats>(API.APPLICATIONS.STATS(appId))
        .then((s) => {
          if (!cancelled) setStats(s);
        })
        .catch(() => {
          // keep whatever we had; the card below shows a fallback
        });
    void loadStats();
    const timer = setInterval(() => void loadStats(), 10000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [validId, online, appId]);

  useEffect(() => {
    const clock = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(clock);
  }, []);

  if (!validId || app === null) {
    return (
      <div className="mx-auto max-w-6xl p-6 sm:p-8">
        <BackLink />
        <Card className="p-8 text-center">
          <h1 className="text-lg font-semibold text-slate-900 dark:text-white">Application not found</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-gh-muted">
            It may have been deleted, or it isn't assigned to you.
          </p>
        </Card>
      </div>
    );
  }

  if (app === undefined) {
    return error ? (
      <div className="mx-auto max-w-6xl p-6 sm:p-8">
        <BackLink />
        <Alert>{error}</Alert>
      </div>
    ) : (
      <LoadingBlock label="Loading application…" className="p-8" />
    );
  }

  const runningTime = app.online ? formatRunningTime(app.startedAt) : null;

  return (
    <div className="mx-auto max-w-6xl p-6 sm:p-8">
      <BackLink />
      <PageHeader
        title={app.name}
        description={runningTime ? `Running for ${runningTime}` : undefined}
        actions={<Badge tone={app.online ? 'online' : 'offline'}>{app.online ? 'Online' : 'Offline'}</Badge>}
      />

      {error && <Alert className="mb-4">{error}</Alert>}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-1">
          <Section title="Status">
            <dl>
              <Row label="State">{app.online ? 'Online' : 'Offline'}</Row>
              <Row label="Started">{formatWhen(app.startedAt)}</Row>
              {runningTime && <Row label="Running for">{runningTime}</Row>}
              {stats && <Row label="Added">{new Date(stats.createdAt).toLocaleDateString()}</Row>}
            </dl>
          </Section>

          <Section title="Uptime history">
            {stats ? (
              <>
                <dl>
                  <Row label="Total uptime">{formatDuration(stats.totalUptimeSeconds)}</Row>
                  <Row label="Total downtime">{formatDuration(stats.totalDowntimeSeconds)}</Row>
                  <Row label="Last ran">{stats.lastRanAt ? formatWhen(stats.lastRanAt) : 'Never'}</Row>
                  <Row label={stats.currentlyOnline ? 'Online since' : 'Offline since'}>
                    {formatWhen(stats.currentStreakStartedAt)}
                  </Row>
                  <Row label="Last went online">{formatWhen(stats.lastWentOnlineAt)}</Row>
                  <Row label="Last went offline">{formatWhen(stats.lastWentOfflineAt)}</Row>
                </dl>
                {stats.recentTransitions.length > 0 && (
                  <div className="mt-4 border-t border-slate-200 pt-3 dark:border-gh-border">
                    <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-gh-muted">
                      Recent changes
                    </p>
                    <ul className="space-y-1.5">
                      {stats.recentTransitions.map((t) => (
                        <li key={`${t.at}-${t.online}`} className="flex items-center justify-between gap-2 text-xs">
                          <Badge tone={t.online ? 'online' : 'offline'}>{t.online ? 'Online' : 'Offline'}</Badge>
                          <span className="text-slate-500 dark:text-gh-muted">{formatWhen(t.at)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            ) : (
              <LoadingBlock label="Loading history…" className="justify-start py-1" />
            )}
          </Section>
        </div>

        <div className="lg:col-span-2">
          <HealthPanel applicationId={app.id} />
        </div>
      </div>
    </div>
  );
}
