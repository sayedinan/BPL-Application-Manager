import type { BadgeTone } from '@/components/ui/Badge';
import { healthIsStale, maintenanceActive } from '@/health/HealthBadge';
import type { HealthLatest } from '@/health/types';

export type AppState = 'offline' | 'degraded' | 'maintenance' | 'healthy';

export function classifyApp(app: { online: boolean }, health: HealthLatest | undefined): AppState {
  if (maintenanceActive(health)) return 'maintenance';
  if (!app.online) return 'offline';
  if (health?.monitored && health.checkedAt) {
    if (healthIsStale(health) || health.reachable === false) return 'degraded';
    if (health.status === 'DEGRADED' || health.status === 'DOWN' || health.status == null) return 'degraded';
  }
  return 'healthy';
}

export interface StateBadge {
  tone: BadgeTone;
  label: string;
}

/**
 * Badge for a classified application. An offline application that its own health
 * check also cannot reach has most likely crashed, so it gets the danger tone;
 * an offline one with no such signal keeps the quiet gray (it may be stopped on purpose).
 */
export function stateBadge(state: AppState, health: HealthLatest | undefined): StateBadge {
  switch (state) {
    case 'healthy':
      return { tone: 'online', label: 'Healthy' };
    case 'degraded':
      return { tone: 'pending', label: 'Degraded' };
    case 'maintenance':
      return { tone: 'maintenance', label: 'Maintenance' };
    case 'offline':
      return health?.monitored && health.reachable === false
        ? { tone: 'error', label: 'Offline' }
        : { tone: 'offline', label: 'Offline' };
  }
}

export const STATE_ORDER: Record<AppState, number> = {
  offline: 0,
  degraded: 1,
  maintenance: 2,
  healthy: 3,
};
