// Shapes returned by the health endpoints (HealthController) and the
// normalized snapshot inside them (HEALTH_CONTRACT.md). Every snapshot
// field except status and timestamp is optional: a missing field means
// "the application did not provide it", so the UI hides that part.

export type HealthStatus = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export interface HealthCheckItem {
  name: string;
  type?: string;
  status: HealthStatus;
  latencyMs?: number;
  message?: string;
}

export interface HealthSnapshot {
  schemaVersion: string;
  status: HealthStatus;
  timestamp: string;
  startedAt?: string;
  app?: {
    name?: string;
    description?: string;
    environment?: string;
    version?: string;
    owner?: string;
    contact?: string;
    links?: { app?: string; docs?: string; runbook?: string };
  };
  build?: { commit?: string; branch?: string; deployedAt?: string };
  resources?: {
    cpu?: { processPercent?: number; systemPercent?: number };
    memory?: { usedMb?: number; limitMb?: number; usedPercent?: number };
    disk?: { totalGb?: number; freeGb?: number; usedPercent?: number };
  };
  checks?: HealthCheckItem[];
  traffic?: {
    windowMinutes?: number;
    requestCount?: number;
    errorCount?: number;
    avgLatencyMs?: number;
  };
  maintenance?: { enabled?: boolean; message?: string; until?: string };
  jobs?: { name: string; lastRunAt?: string; lastResult?: string; nextRunAt?: string }[];
}

/** GET /applications/{id}/health */
export interface HealthLatest {
  monitored: boolean;
  pollIntervalSeconds?: number;
  /** null/absent until the first check lands */
  checkedAt?: string | null;
  reachable?: boolean;
  httpStatus?: number | null;
  responseMs?: number | null;
  /** null when there was no usable response */
  status?: HealthStatus | null;
  error?: string | null;
  consecutiveFailures?: number;
  sslNotAfter?: string | null;
  sslDaysRemaining?: number | null;
  snapshot?: HealthSnapshot | null;
  /** Present while a maintenance window set in the dashboard is open (alerts held back). */
  maintenanceUntil?: string | null;
  maintenanceNote?: string | null;
  maintenanceBy?: string | null;
}

/** GET /applications/{id}/health/history */
export interface HealthHistory {
  hours: number;
  bucketSeconds: number;
  checks: number;
  failedChecks: number;
  /** share of checks that succeeded, null when there is no data yet */
  availabilityPercent: number | null;
  points: {
    t: string;
    checks: number;
    failedChecks: number;
    avgResponseMs: number | null;
    /** Averages of the readings in this bucket; null when none were reported. */
    avgCpuPercent?: number | null;
    avgMemoryPercent?: number | null;
    avgDiskPercent?: number | null;
  }[];
}

/** GET /applications/{id}/health/config (SYS_ADMIN) */
export interface HealthConfig {
  configured: boolean;
  enabled?: boolean;
  url?: string;
  format?: 'CONTRACT' | 'ACTUATOR';
  hasApiKey?: boolean;
  tlsPinSha256?: string | null;
  pollIntervalSeconds?: number;
}

/** POST /applications/{id}/health/test (SYS_ADMIN) */
export interface HealthTestResult {
  ok: boolean;
  httpStatus: number | null;
  responseMs: number | null;
  status: HealthStatus | null;
  error: string | null;
  sslNotAfter: string | null;
  sslDaysRemaining: number | null;
  snapshot: HealthSnapshot | null;
}
