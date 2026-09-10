# `audit-logs/`

Admin+ read-only `/audit-logs` view (SPEC §4.3, §9.2) and the
WebSocket subscription to `/topic/audit-log` for live push (SPEC §5.2).

Planned files:

- `AuditLogPage.tsx` — paginated table with `from` / `to` date
  filters (SPEC §4.3)
- `hooks/useAuditLogs.ts` — paginated fetch via TanStack Query
- `hooks/useAuditLogSubscription.ts` — STOMP subscriber that
  prepends new rows to the table
- `components/AuditLogTable.tsx`
