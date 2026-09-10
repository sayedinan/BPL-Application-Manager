export function CreateAdminPage() {
  // 13.2 — Sys.Admin only; Admin cannot create other Admins (§1 ceiling)
  return <div><h2>Create Admin (Sys.Admin only)</h2><button>Create Admin</button></div>;
}
