// 13.1 — Users management UI (Admin+ only); create/delete; no-self-deletion (Phase 6.2)
export function UsersPage() {
  return (
    <div>
      <h2>Users</h2>
      <button>Create User</button>
      <button>Delete User (not self)</button>
      <div>/* User list with role + assigned applications */</div>
    </div>
  );
}
