import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { Card, PageHeader } from '@/components/ui/Card';
import { Alert } from '@/components/ui/Alert';

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

const inputClass =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-slate-700 dark:bg-surface-dark dark:text-slate-100';
const labelClass = 'mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300';

function roleTone(role: User['role']): BadgeTone {
  if (role === 'SYS_ADMIN') return 'error';
  if (role === 'ADMIN') return 'neutral';
  return 'offline';
}

function roleLabel(role: User['role']): string {
  if (role === 'SYS_ADMIN') return 'Sys.Admin';
  if (role === 'ADMIN') return 'Admin';
  return 'User';
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
  const [newRole, setNewRole] = useState<'USER' | 'ADMIN' | 'SYS_ADMIN'>('USER');
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
      const path = newRole === 'USER' ? API.USERS.CREATE : '/users/create-admin';
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
    <div className="p-6 sm:p-8 max-w-6xl mx-auto">
      <PageHeader
        title="Users"
        description="Manage accounts, roles, and application assignments."
        actions={
          <Button
            variant={showForm ? 'secondary' : 'primary'}
            onClick={() => { setShowForm((s) => !s); resetForm(); }}
          >
            {showForm ? 'Cancel' : '+ Add User'}
          </Button>
        }
      />

      {error && <Alert className="mb-4">{error}</Alert>}

      {createdResult && (
        <Card className="mb-6 max-w-xl border-status-online/30 bg-status-onlineBg/60 p-5 dark:border-green-500/20 dark:bg-green-500/5">
          <p className="mb-1 font-semibold text-status-online dark:text-green-400">
            User &quot;{createdResult.username}&quot; created.
          </p>
          <p className="mb-2 text-sm text-slate-600 dark:text-slate-300">
            Temporary password (shown once — deliver this out-of-band; it cannot be retrieved again):
          </p>
          <code className="block break-all rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-sm dark:border-slate-700 dark:bg-surface-dark dark:text-slate-100">
            {createdResult.temporaryPassword}
          </code>
          <Button size="sm" variant="secondary" className="mt-3" onClick={() => setCreatedResult(null)}>
            Dismiss
          </Button>
        </Card>
      )}

      {showForm && (
        <Card className="mb-6 max-w-md animate-fade-in p-5">
          <form onSubmit={handleCreate} noValidate>
            <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-white">New User</h2>
            <label className="mb-3 block">
              <span className={labelClass}>Username</span>
              <input
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                className={inputClass}
                required
              />
            </label>
            <label className="mb-4 block">
              <span className={labelClass}>Role</span>
              <select
                value={newRole}
                onChange={(e) => setNewRole(e.target.value as 'USER' | 'ADMIN' | 'SYS_ADMIN')}
                className={inputClass}
              >
                <option value="USER">User</option>
                {canCreateAdmin && <option value="ADMIN">Admin</option>}
                {canCreateAdmin && <option value="SYS_ADMIN">Sys.Admin</option>}
              </select>
              {!canCreateAdmin && (
                <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">
                  Only Sys.Admin can create Admin accounts.
                </span>
              )}
            </label>
            {formError && <Alert className="mb-4">{formError}</Alert>}
            <Button type="submit" disabled={creating}>
              {creating ? 'Creating…' : 'Create User'}
            </Button>
          </form>
        </Card>
      )}

      {loading ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">Loading…</p>
      ) : users.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">No users yet.</Card>
      ) : (
        <Card className="overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                <th className="px-4 py-3 font-medium">Username</th>
                <th className="px-4 py-3 font-medium">Role</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Created</th>
                <th className="px-4 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const isSelf = currentUser?.id === u.id;
                const isSysAdminRow = u.role === 'SYS_ADMIN';
                const viewerIsSysAdmin = currentUser?.role === 'SYS_ADMIN';
                const canManageRow = viewerIsSysAdmin || !isSysAdminRow;
                return (
                  <tr key={u.id} className="border-b border-slate-100 last:border-0 dark:border-slate-800/60">
                    <td className="px-4 py-3 font-medium text-slate-900 dark:text-white">
                      {u.username}
                      {isSelf && <span className="ml-2 text-xs font-normal text-slate-400">(you)</span>}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={roleTone(u.role)} dot={false}>{roleLabel(u.role)}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      {u.must_change_password && (
                        <Badge tone="pending">Must change password</Badge>
                      )}
                    </td>
                    <td className="px-4 py-3 text-slate-500 dark:text-slate-400">
                      {new Date(u.created_at).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      {canManageRow && (
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => openEdit(u)}
                            title={isSelf ? 'Can edit assignments only (cannot delete self)' : undefined}
                          >
                            Edit
                          </Button>
                          <Button
                            size="sm"
                            variant="danger"
                            disabled={isSelf || deletingId === u.id}
                            onClick={() => handleDelete(u)}
                            title={isSelf ? 'Cannot delete your own account' : undefined}
                          >
                            {deletingId === u.id ? 'Deleting…' : 'Delete'}
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}

      {editingUser && (
        <Card className="mt-6 max-w-md animate-fade-in p-5">
          <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-white">
            Edit &quot;{editingUser.username}&quot; — Assigned Applications
          </h2>

          <fieldset className="mb-4">
            <legend className={labelClass}>Assigned Applications</legend>
            {applications.length === 0 ? (
              <p className="text-xs text-slate-500 dark:text-slate-400">No applications available.</p>
            ) : (
              <div className="space-y-1.5">
                {applications.map((app) => (
                  <label key={app.id} className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                    <input
                      type="checkbox"
                      checked={editAssignedIds.includes(app.id)}
                      onChange={() => toggleAssigned(app.id)}
                      className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500 dark:border-slate-600"
                    />
                    {app.name}
                  </label>
                ))}
              </div>
            )}
          </fieldset>

          {editError && <Alert className="mb-4">{editError}</Alert>}

          <div className="flex gap-2">
            <Button onClick={handleSaveEdit} disabled={savingEdit}>
              {savingEdit ? 'Saving…' : 'Save'}
            </Button>
            <Button variant="secondary" onClick={() => setEditingUser(null)}>
              Cancel
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
