import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { DayStrip } from '@/health/DayStrip';
import { describeHealth, maintenanceActive, timeAgo } from '@/health/HealthBadge';
import type { HealthLatest } from '@/health/types';
import { stateBadge, type AppState } from '@/lib/appState';
import { formatUptime } from '@/lib/duration';
import { appPath } from '@/lib/appRoute';

export interface ApplicationSummary {
  id: number;
  name: string;
  online: boolean;
  startedAt: string | null;
  groupName: string | null;
}

/** Shared column template: the header row and every data row must line up. */
export const ROW_GRID =
  'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 ' +
  'md:grid-cols-[8.5rem_minmax(0,1fr)_17rem_5.5rem]';

/**
 * Uptime readout. Re-renders once a minute (the text only changes that often),
 * so a long list of rows isn't redrawn every second.
 */
function RunningFor({ startedAt }: { startedAt: string }): JSX.Element | null {
  const [, setTick] = useState(0);
  useEffect(() => {
    const clock = setInterval(() => setTick((t) => t + 1), 60000);
    return () => clearInterval(clock);
  }, []);
  const text = formatUptime(startedAt);
  if (!text) return null;
  return <span title={`Started ${new Date(startedAt).toLocaleString()}`}>Up {text}</span>;
}

/**
 * One plain line about the latest health reading, e.g. "Unreachable · 120 ms · checked 8s ago".
 * The health label is dropped when the status badge already says the same thing.
 */
function healthLine(
  health: HealthLatest | undefined,
  healthLoaded: boolean,
  badgeLabel: string,
): string | null {
  if (!health) return healthLoaded ? 'Health data unavailable' : null;
  if (!health.monitored) return 'Health not monitored';
  if (maintenanceActive(health)) {
    const until = new Date(health.maintenanceUntil as string).toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
    return `Until ${until} · alerts held back`;
  }
  const parts: string[] = [];
  const label = describeHealth(health).label;
  if (label.toLowerCase() !== badgeLabel.toLowerCase()) parts.push(label);
  if (health.responseMs != null) parts.push(`${health.responseMs} ms`);
  parts.push(health.checkedAt ? `checked ${timeAgo(health.checkedAt)}` : 'waiting for the first check');
  return parts.join(' · ');
}

interface ApplicationRowProps {
  app: ApplicationSummary;
  state: AppState;
  health: HealthLatest | undefined;
  healthLoaded: boolean;
  pending: 'start' | 'stop' | null;
  /** True for a moment after a start/stop finishes, so a late second tap can't undo it. */
  cooling: boolean;
  onStart: (app: ApplicationSummary) => void;
  /** Stop asks for confirmation first; the Dashboard owns that dialog. */
  onRequestStop: (app: ApplicationSummary) => void;
}

// 44px tall on touch, compact on pointer devices.
const ACTION_SIZE = 'min-h-11 min-w-[4.5rem] md:min-h-0';

export function ApplicationRow({
  app,
  state,
  health,
  healthLoaded,
  pending,
  cooling,
  onStart,
  onRequestStop,
}: ApplicationRowProps): JSX.Element {
  const badge = stateBadge(state, health);
  const line = healthLine(health, healthLoaded, badge.label);
  const monitored = health?.monitored === true;

  return (
    <li className={`${ROW_GRID} px-4 py-3`}>
      {/* DOM order is name, action, strip so keyboard and screen-reader order reads
          "name, Start/Stop" first; md:order-* puts the status column first visually.
          On phones the badge sits on the name line (saves a row); on wide screens
          it is its own column. */}
      <div className="min-w-0 md:order-2">
        <div className="flex min-w-0 items-center gap-2">
          <span className="shrink-0 md:hidden">
            <Badge tone={badge.tone}>{badge.label}</Badge>
          </span>
          <Link
            to={appPath(app.name)}
            className="block truncate font-medium text-ink hover:text-accent hover:underline"
            title={`${app.name}: open details`}
          >
            {app.name}
          </Link>
        </div>
        <p className="mt-0.5 text-xs text-muted md:truncate">
          {app.online && app.startedAt ? <RunningFor startedAt={app.startedAt} /> : app.online ? 'Online' : 'Not running'}
          {line && <span> · {line}</span>}
        </p>
      </div>

      <div className="justify-self-end md:order-4 md:w-full">
        {pending ? (
          <Button size="sm" variant="secondary" loading className={`w-full ${ACTION_SIZE}`}>
            {pending === 'start' ? 'Starting…' : 'Stopping…'}
          </Button>
        ) : app.online ? (
          <Button
            size="sm"
            variant="dangerOutline"
            disabled={cooling}
            onClick={() => onRequestStop(app)}
            className={`w-full ${ACTION_SIZE}`}
            aria-label={`Stop ${app.name}`}
          >
            Stop
          </Button>
        ) : (
          <Button
            size="sm"
            variant="primary"
            disabled={cooling}
            onClick={() => onStart(app)}
            className={`w-full ${ACTION_SIZE}`}
            aria-label={`Start ${app.name}`}
          >
            Start
          </Button>
        )}
      </div>

      <div className="hidden md:order-1 md:block">
        <Badge tone={badge.tone}>{badge.label}</Badge>
      </div>

      <div className="col-span-2 md:order-3 md:col-span-1">
        {monitored ? (
          <DayStrip applicationId={app.id} />
        ) : (
          <p className="text-xs text-muted">{healthLoaded ? 'Not monitored' : ''}</p>
        )}
      </div>
    </li>
  );
}
