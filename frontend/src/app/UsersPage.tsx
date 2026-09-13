import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';

interface User {
  id: number;
  username: string;
  role: 'SYS_ADMIN' | 'ADMIN' | 'USER';
  must_change_password: boolean;
  created_at: string;
  assignedApplicationIds: number[];
}

interface Application {
  id: number;
  name: string;
}

export function UsersPage(): JSX.Element {
  const { user: currentUser } = useAuth();
  const canCreateAdmin = currentUser?.role === 'SYS_ADMIN';

  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const [applications, setApplications] = useState<Application[]>([]);
  const [editingUser, setEditingUser] = useState<User | null>(null);
  const [editRole, setEditRole] = useState<'USER' | 'ADMIN' | 'SYS_ADMIN'>('USER');
  const [editAssignedIds, setEditAssignedIds] = useState<number[]>([]);
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [newUsername, setNewUsername] = useState('');
  const [newRole, setNewRole] = useState<'USER' | 'ADMIN'>('USER');
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Shown exactly once after a successful create — same pattern as
  // reset-password: the cleartext temp password never appears again
  // after this render, so it must be copy-able right here.
  const [createdResult, setCreatedResult] = useState<{ username: string; temporaryPassword: string } | null>(null);

  async function loadUsers() {
    try {
      const list = await api.get<User[]>(API.USERS.LIST);
      setUsers(list);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load users.');
    } finally {
      setLoading(false);
    }
  }

  async function loadApplications() {
    try {
      const list = await api.get<Application[]>(API.APPLICATIONS.LIST);
      setApplications(list);
    } catch {
      // Non-fatal — assignment checkboxes just won't render.
    }
  }

  useEffect(() => { void loadUsers(); void loadApplications(); }, []);

  function resetForm() {
    setNewUsername('');
    setNewRole('USER');
    setFormError(null);
  }

  function openEdit(u: User) {
    setEditingUser(u);
    setEditRole(u.role);
    setEditAssignedIds(u.assignedApplicationIds ?? []);
    setEditError(null);
  }

  function toggleAssigned(appId: number) {
    setEditAssignedIds((ids) =>
      ids.includes(appId) ? ids.filter((i) => i !== appId) : [...ids, appId]
    );
  }

  async function handleSaveEdit() {
    if (!editingUser) return;
    setSavingEdit(true);
    setEditError(null);
    try {
      await api.put(API.USERS.UPDATE(editingUser.id), {
        role: editRole,
        assignedApplicationIds: editAssignedIds,
      });
      setEditingUser(null);
      await loadUsers();
    } catch (err) {
      setEditError(err instanceof ApiError ? err.message : 'Failed to update user.');
    } finally {
      setSavingEdit(false);
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!newUsername.trim()) {
      setFormError('Username is required.');
      return;
    }
    setCreating(true);
    try {
      const path = newRole === 'ADMIN' ? '/users/create-admin' : API.USERS.CREATE;
      const res = await api.post<{ id: number; username: string; role: string; temporaryPassword: string }>(
        path,
        { username: newUsername.trim(), role: newRole, assignedApplicationIds: [] },
      );
      setCreatedResult({ username: res.username, temporaryPassword: res.temporaryPassword });
      resetForm();
      setShowForm(false);
      await loadUsers();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Failed to create user.');
    } finally {
      setCreating(false);
    }
  }

  async function handleDelete(user: User) {
    if (currentUser && user.id === currentUser.id) {
      // Defense in depth — the backend also rejects this
      // (SELF_DELETE_FORBIDDEN), but no point round-tripping.
      setError('You cannot delete your own account.');
      return;
    }
    if (!window.confirm(`Delete user "${user.username}"? This cannot be undone.`)) return;
    setDeletingId(user.id);
    try {
      await api.delete(API.USERS.DELETE(user.id));
      await loadUsers();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Failed to delete ${user.username}.`);
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">Users</h1>
        <button
          onClick={() => { setShowForm((s) => !s); resetForm(); }}
          className="text-sm px-3 py-2 bg-blue-600 text-white rounded"
        >
          {showForm ? 'Cancel' : '+ Add User'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}

      {createdResult && (
        <div className="border border-green-300 bg-green-50 rounded p-4 mb-6 max-w-xl">
          <p className="font-medium text-green-800 mb-1">
            User &quot;{createdResult.username}&quot; created.
          </p>
          <p className="text-sm text-gray-700 mb-2">
            Temporary password (shown once — deliver this out-of-band; it cannot be retrieved again):
          </p>
          <code className="block bg-white border rounded px-3 py-2 font-mono text-sm break-all">
            {createdResult.temporaryPassword}
          </code>
          <button
            onClick={() => setCreatedResult(null)}
            className="text-xs px-3 py-1 mt-3 border rounded"
          >
            Dismiss
          </button>
        </div>
      )}

      {showForm && (
        <form onSubmit={handleCreate} noValidate className="border rounded p-4 mb-6 max-w-md">
          <h2 className="font-medium mb-3">New User</h2>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Username</span>
            <input
              value={newUsername}
              onChange={(e) => setNewUsername(e.target.value)}
              className="w-full border rounded px-3 py-2"
              required
            />
          </label>
          <label className="block mb-4">
            <span className="block text-sm text-gray-700 mb-1">Role</span>
            <select
              value={newRole}
              onChange={(e) => setNewRole(e.target.value as 'USER' | 'ADMIN')}
              className="w-full border rounded px-3 py-2"
            >
              <option value="USER">User</option>
              {canCreateAdmin && <option value="ADMIN">Admin</option>}
            </select>
            {!canCreateAdmin && (
              <span className="block text-xs text-gray-500 mt-1">
                Only Sys.Admin can create Admin accounts.
              </span>
            )}
          </label>
          {formError && <p role="alert" className="text-sm text-red-600 mb-4">{formError}</p>}
          <button
            type="submit"
            disabled={creating}
            className="text-sm px-4 py-2 bg-blue-600 text-white rounded disabled:opacity-50"
          >
            {creating ? 'Creating…' : 'Create User'}
          </button>
        </form>
      )}

      {loading ? (
        <p>Loading…</p>
      ) : users.length === 0 ? (
        <p className="text-gray-600">No users yet.</p>
      ) : (
        <table className="w-full text-sm border-collapse">
          <thead>
            <tr className="text-left border-b">
              <th className="py-2">Username</th>
              <th className="py-2">Role</th>
              <th className="py-2">Status</th>
              <th className="py-2">Created</th>
              <th className="py-2"></th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => {
              const isSelf = currentUser?.id === u.id;
              return (
                <tr key={u.id} className="border-b">
                  <td className="py-2">
                    {u.username}
                    {isSelf && <span className="ml-2 text-xs text-gray-500">(you)</span>}
                  </td>
                  <td className="py-2">
                    <span
                      className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                        u.role === 'SYS_ADMIN'
                          ? 'bg-red-100 text-red-800'
                          : u.role === 'ADMIN'
                            ? 'bg-blue-100 text-blue-800'
                            : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {u.role}
                    </span>
                  </td>
                  <td className="py-2">
                    {u.must_change_password && (
                      <span className="text-xs text-orange-600">⚠ Must change password</span>
                    )}
                  </td>
                  <td className="py-2 text-gray-600">{new Date(u.created_at).toLocaleDateString()}</td>
                  <td className="py-2 text-right space-x-2">
                    <button
                      onClick={() => openEdit(u)}
                      className="text-xs px-3 py-1 bg-gray-200 text-gray-800 rounded"
                    >
                      Edit
                    </button>
                    <button
                      disabled={isSelf || deletingId === u.id}
                      onClick={() => handleDelete(u)}
                      title={isSelf ? 'Cannot delete your own account' : undefined}
                      className="text-xs px-3 py-1 bg-red-600 text-white rounded disabled:opacity-40"
                    >
                      {deletingId === u.id ? 'Deleting…' : 'Delete'}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {editingUser && (
        <div className="border rounded p-4 mt-6 max-w-md">
          <h2 className="font-medium mb-3">Edit &quot;{editingUser.username}&quot;</h2>

          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Role</span>
            <select
              value={editRole}
              onChange={(e) => setEditRole(e.target.value as 'USER' | 'ADMIN' | 'SYS_ADMIN')}
              className="w-full border rounded px-3 py-2"
            >
              <option value="USER">User</option>
              {canCreateAdmin && <option value="ADMIN">Admin</option>}
              {canCreateAdmin && <option value="SYS_ADMIN">Sys.Admin</option>}
            </select>
            {!canCreateAdmin && (
              <span className="block text-xs text-gray-500 mt-1">
                Only Sys.Admin can promote to Admin or Sys.Admin.
              </span>
            )}
          </label>

          <fieldset className="mb-4">
            <legend className="block text-sm text-gray-700 mb-2">Assigned Applications</legend>
            {applications.length === 0 ? (
              <p className="text-xs text-gray-500">No applications available.</p>
            ) : (
              applications.map((app) => (
                <label key={app.id} className="flex items-center gap-2 text-sm mb-1">
                  <input
                    type="checkbox"
                    checked={editAssignedIds.includes(app.id)}
                    onChange={() => toggleAssigned(app.id)}
                  />
                  {app.name}
                </label>
              ))
            )}
          </fieldset>

          {editError && <p role="alert" className="text-sm text-red-600 mb-3">{editError}</p>}

          <div className="flex gap-2">
            <button
              onClick={handleSaveEdit}
              disabled={savingEdit}
              className="text-sm px-4 py-2 bg-blue-600 text-white rounded disabled:opacity-50"
            >
              {savingEdit ? 'Saving…' : 'Save'}
            </button>
            <button
              onClick={() => setEditingUser(null)}
              className="text-sm px-4 py-2 border rounded"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
