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

export const STATE_ORDER: Record<AppState, number> = {
  offline: 0,
  degraded: 1,
  maintenance: 2,
  healthy: 3,
};
