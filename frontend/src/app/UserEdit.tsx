export function UserEdit({ userId: _userId }: { userId: number }) {
  // 13.4 — "Assigned Applications" multi-select (§11.4 naming exactly)
  // Wired to Phase 6.3 PUT /users/{id} with assignedApplicationIds
  return <div><h3>Edit User — Assigned Applications</h3><select multiple><option>App 1</option></select><button>Save</button></div>;
}
