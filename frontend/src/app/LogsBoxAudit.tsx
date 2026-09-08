export function AuditLogOption() {
  // 12.4 — Admin/Sys.Admin only; wired to Phase 9.4 GET /audit-logs (paginated, from/to) and /topic/audit-log (Admin+ only)
  return <div><select><option>Audit Log (Admin+ only)</option></select></div>;
}
