import { describe, expect, it } from 'vitest';
import {
  actionLabel,
  actorLabel,
  detailText,
  EMPTY_AUDIT_FILTERS,
  filterAuditRows,
  filterOptions,
  hasActiveFilters,
  targetLabel,
  type AuditLogRow,
} from './auditFormat';

function row(over: Partial<AuditLogRow>): AuditLogRow {
  return {
    id: 1,
    timestamp: '2026-11-20T10:00:00',
    actorUsername: 'inan',
    actorRole: 'SYS_ADMIN',
    actionType: 'STOP_APPLICATION',
    targetApplicationId: 7,
    targetApplicationName: 'ledger-sync',
    targetUserId: null,
    detail: null,
    result: 'SUCCESS',
    ...over,
  };
}

const rows: AuditLogRow[] = [
  row({ id: 1, timestamp: '2026-11-19T23:30:00' }),
  row({ id: 2, actorUsername: 'admin2', actorRole: 'ADMIN', actionType: 'UPDATE_APPLICATION', targetApplicationName: 'invoice-api', timestamp: '2026-11-20T09:00:00', detail: { script: 'start' } }),
  row({ id: 3, actorUsername: 'system', actorRole: 'SYSTEM', actionType: 'APPLICATION_OFFLINE', targetApplicationName: 'reports', timestamp: '2026-11-21T01:00:00' }),
  row({ id: 4, actionType: 'RESET_PASSWORD', targetApplicationId: null, targetApplicationName: null, targetUserId: 12, result: 'FAILURE', timestamp: '2026-11-21T12:00:00' }),
];

describe('labels', () => {
  it('maps known action types to plain language', () => {
    expect(actionLabel('START_APPLICATION')).toBe('Started application');
  });

  it('humanizes unknown action types instead of showing the raw code', () => {
    expect(actionLabel('SOME_NEW_ACTION')).toBe('Some new action');
  });

  it('shows system events as "System" regardless of the stored username', () => {
    expect(actorLabel(rows[2])).toBe('System');
  });

  it('falls back from application name to id to user id', () => {
    expect(targetLabel(rows[0])).toBe('ledger-sync');
    expect(targetLabel(row({ targetApplicationName: null }))).toBe('Application #7');
    expect(targetLabel(rows[3])).toBe('User #12');
  });

  it('summarises detail and ignores empty values', () => {
    expect(detailText(rows[1])).toBe('script: start');
    expect(detailText(row({ detail: { a: '', b: null } }))).toBe('');
  });
});

describe('filterAuditRows', () => {
  it('returns everything when no filter is set', () => {
    expect(hasActiveFilters(EMPTY_AUDIT_FILTERS)).toBe(false);
    expect(filterAuditRows(rows, EMPTY_AUDIT_FILTERS)).toHaveLength(4);
  });

  it('filters by user, matching the displayed name', () => {
    const out = filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, user: 'System' });
    expect(out.map((r) => r.id)).toEqual([3]);
  });

  it('filters by action type and application', () => {
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, action: 'UPDATE_APPLICATION' }).map((r) => r.id)).toEqual([2]);
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, app: 'ledger-sync' }).map((r) => r.id)).toEqual([1]);
  });

  it('treats the end date as inclusive of the whole day', () => {
    const out = filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, from: '2026-11-20', to: '2026-11-20' });
    expect(out.map((r) => r.id)).toEqual([2]);
  });

  it('searches across actor, action, target, detail and result', () => {
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, search: 'failure' }).map((r) => r.id)).toEqual([4]);
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, search: 'script: start' }).map((r) => r.id)).toEqual([2]);
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, search: 'came online' })).toHaveLength(0);
    expect(filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, search: 'went offline' }).map((r) => r.id)).toEqual([3]);
  });

  it('combines filters with AND', () => {
    const out = filterAuditRows(rows, { ...EMPTY_AUDIT_FILTERS, user: 'inan', search: 'reset' });
    expect(out.map((r) => r.id)).toEqual([4]);
  });
});

describe('filterOptions', () => {
  it('lists distinct, sorted values for the dropdowns', () => {
    const o = filterOptions(rows);
    expect(o.users).toEqual(['admin2', 'inan', 'System']);
    expect(o.apps).toEqual(['invoice-api', 'ledger-sync', 'reports']);
    // Sorted by the label people see, not by the raw code.
    expect(o.actions).toEqual(['RESET_PASSWORD', 'STOP_APPLICATION', 'UPDATE_APPLICATION', 'APPLICATION_OFFLINE']);
  });
});
