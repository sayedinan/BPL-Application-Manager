import { Badge, type BadgeTone } from '@/components/ui/Badge';
import type { HealthLatest } from './types';

/** "8s ago", "5m ago", "3h ago", "2d ago"; a dash when there is no time. */
export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export interface HealthPresentation {
  tone: BadgeTone;
  label: string;
}

/**
 * One plain-language label and colour for the latest health reading.
 * Business viewers see "Healthy / Degraded / Down", not raw statuses.
 */
export function describeHealth(health: HealthLatest): HealthPresentation {
  if (!health.checkedAt) return { tone: 'pending', label: 'Checking…' };

  // If readings stop arriving, say so instead of showing an old "Healthy".
  const ageSeconds = (Date.now() - new Date(health.checkedAt).getTime()) / 1000;
  const staleAfterSeconds = Math.max(60, 4 * (health.pollIntervalSeconds ?? 10));
  if (ageSeconds > staleAfterSeconds) return { tone: 'offline', label: 'No recent data' };

  if (health.reachable === false) return { tone: 'error', label: 'Unreachable' };
  switch (health.status) {
    case 'UP':
      return { tone: 'online', label: 'Healthy' };
    case 'DEGRADED':
      return { tone: 'pending', label: 'Degraded' };
    case 'DOWN':
      return { tone: 'error', label: 'Down' };
    case 'UNKNOWN':
      return { tone: 'offline', label: 'Unknown' };
    default:
      return { tone: 'error', label: 'Bad response' };
  }
}

export function HealthBadge({ health }: { health: HealthLatest }): JSX.Element {
  const { tone, label } = describeHealth(health);
  return <Badge tone={tone}>{label}</Badge>;
}

/** Compact health row for a Dashboard card. The full picture is on the application's page. */
export function HealthSummary({ health }: { health: HealthLatest | undefined }): JSX.Element | null {
  if (!health) return null;
  if (!health.monitored) {
    return <p className="mt-3 text-xs text-slate-500 dark:text-gh-muted">Health: not monitored</p>;
  }
  return (
    <div className="mt-3 rounded-md border border-slate-200 bg-white px-3 py-2 dark:border-gh-border dark:bg-gh-subtle">
      <HealthBadge health={health} />
      <p className="mt-1 truncate text-xs text-slate-500 dark:text-gh-muted">
        {health.responseMs != null ? `${health.responseMs} ms · ` : ''}
        {health.checkedAt ? `checked ${timeAgo(health.checkedAt)}` : 'waiting for the first check'}
      </p>
    </div>
  );
}
