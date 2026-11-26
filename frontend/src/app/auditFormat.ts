// Presentation and filtering for audit-log rows (GET /audit-logs and the
// /topic/audit-log live topic). Pure functions, so they are easy to test.

export interface AuditLogRow {
  id?: number;
  timestamp: string;
  actorUsername: string;
  actorRole: string;
  actionType: string;
  targetApplicationId: number | null;
  targetApplicationName: string | null;
  targetUserId: number | null;
  detail?: Record<string, unknown> | null;
  result: string;
}

/** Plain-language names for the action types the backend writes (SPEC §3.2). */
const ACTION_LABELS: Record<string, string> = {
  START_APPLICATION: 'Started application',
  STOP_APPLICATION: 'Stopped application',
  CREATE_APPLICATION: 'Created application',
  UPDATE_APPLICATION: 'Updated application',
  DELETE_APPLICATION: 'Deleted application',
  ASSIGN_APPLICATION: 'Changed assigned applications',
  CREATE_USER: 'Created user',
  CREATE_ADMIN: 'Created admin',
  UPDATE_USER: 'Updated user',
  DELETE_USER: 'Deleted user',
  RESET_PASSWORD: 'Reset password',
  CHANGE_PASSWORD: 'Changed password',
  APPLICATION_ONLINE: 'Came online (outside the web app)',
  APPLICATION_OFFLINE: 'Went offline (outside the web app)',
};

const ROLE_LABELS: Record<string, string> = {
  SYS_ADMIN: 'Sys.Admin',
  ADMIN: 'Admin',
  USER: 'User',
  SYSTEM: 'System',
};

/** "SOME_NEW_ACTION" -> "Some new action", so an unknown type still reads sensibly. */
function humanize(code: string): string {
  const spaced = code.toLowerCase().replace(/_/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export const actionLabel = (actionType: string): string => ACTION_LABELS[actionType] ?? humanize(actionType);
export const roleLabel = (role: string): string => ROLE_LABELS[role] ?? role;

export function actorLabel(row: AuditLogRow): string {
  return row.actorRole === 'SYSTEM' ? 'System' : row.actorUsername;
}

export function targetLabel(row: AuditLogRow): string {
  if (row.targetApplicationName) return row.targetApplicationName;
  if (row.targetApplicationId != null) return `Application #${row.targetApplicationId}`;
  if (row.targetUserId != null) return `User #${row.targetUserId}`;
  return '';
}

/** Short "key: value" summary of the row's detail object, empty when there is none. */
export function detailText(row: AuditLogRow): string {
  if (!row.detail) return '';
  return Object.entries(row.detail)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(', ');
}

export const isSuccess = (row: AuditLogRow): boolean => row.result.toUpperCase() === 'SUCCESS';

export interface AuditFilters {
  user: string;
  action: string;
  app: string;
  /** yyyy-mm-dd in the viewer's local time, inclusive. Empty = no bound. */
  from: string;
  to: string;
  search: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = { user: '', action: '', app: '', from: '', to: '', search: '' };

export const hasActiveFilters = (f: AuditFilters): boolean => Object.values(f).some((v) => v !== '');

/** No date can satisfy such a range; the page says so instead of just showing nothing. */
export const isReversedRange = (f: AuditFilters): boolean => f.from !== '' && f.to !== '' && f.from > f.to;

export function filterAuditRows(rows: AuditLogRow[], f: AuditFilters): AuditLogRow[] {
  const fromMs = f.from ? new Date(`${f.from}T00:00:00`).getTime() : null;
  // Inclusive end date: everything before the start of the following day.
  const toMs = f.to ? new Date(`${f.to}T00:00:00`).getTime() + 24 * 60 * 60 * 1000 : null;
  const needle = f.search.trim().toLowerCase();

  return rows.filter((row) => {
    if (f.user && actorLabel(row) !== f.user) return false;
    if (f.action && row.actionType !== f.action) return false;
    if (f.app && row.targetApplicationName !== f.app) return false;
    const t = new Date(row.timestamp).getTime();
    if (fromMs !== null && t < fromMs) return false;
    if (toMs !== null && t >= toMs) return false;
    if (needle) {
      const haystack = [actorLabel(row), actionLabel(row.actionType), targetLabel(row), detailText(row), row.result]
        .join(' ')
        .toLowerCase();
      if (!haystack.includes(needle)) return false;
    }
    return true;
  });
}

/** Distinct, sorted values for the filter dropdowns. */
export function filterOptions(rows: AuditLogRow[]): { users: string[]; actions: string[]; apps: string[] } {
  const users = new Set<string>();
  const actions = new Set<string>();
  const apps = new Set<string>();
  for (const r of rows) {
    users.add(actorLabel(r));
    actions.add(r.actionType);
    if (r.targetApplicationName) apps.add(r.targetApplicationName);
  }
  const sort = (s: Set<string>) => [...s].sort((a, b) => a.localeCompare(b));
  return {
    users: sort(users),
    actions: [...actions].sort((a, b) => actionLabel(a).localeCompare(actionLabel(b))),
    apps: sort(apps),
  };
}
