import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { Card, PageHeader } from '@/components/ui/Card';
import { Modal } from '@/components/ui/Modal';
import { Alert } from '@/components/ui/Alert';
import { LoadingBlock } from '@/components/ui/Spinner';

interface User {
  id: number;
  username: string;
  full_name: string | null;
  role: 'SYS_ADMIN' | 'ADMIN' | 'USER';
  email: string | null;
  phone_number: string | null;
  must_change_password: boolean;
  created_at: string;
  assignedApplicationIds: number[];
  online?: boolean;
}

interface Application {
  id: number;
  name: string;
}

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white shadow-sm px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg';
const labelClass = 'mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft';
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

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

function describeApiError(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) return fallback;
  const d = err.details;
  if (d && typeof d === 'object') {
    const parts = Object.entries(d as Record<string, unknown>).map(
      ([field, msg]) => `${field}: ${String(msg)}`,
    );
    if (parts.length > 0) return `${err.message} (${parts.join('; ')})`;
  }
  return err.message;
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }): JSX.Element {
  return (
    <div className="flex items-start justify-between gap-4 py-2.5 first:pt-0 last:pb-0">
      <dt className="shrink-0 text-slate-500 dark:text-gh-muted">{label}</dt>
      <dd className="min-w-0 break-words text-right font-medium text-slate-900 dark:text-gh-fg">{children}</dd>
    </div>
  );
}

export function UsersPage(): JSX.Element {
  const { user: currentUser } = useAuth();
  const canCreateAdmin = currentUser?.role === 'SYS_ADMIN';

  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [detailsUserId, setDetailsUserId] = useState<number | null>(null);

  const [applications, setApplications] = useState<Application[]>([]);
  const [editingUser, setEditingUser] = useState<User | null>(null);
  const [editUsername, setEditUsername] = useState('');
  const [editFullName, setEditFullName] = useState('');
  const [editEmail, setEditEmail] = useState('');
  const [editRole, setEditRole] = useState<'USER' | 'ADMIN' | 'SYS_ADMIN'>('USER');
  const [editAssignedIds, setEditAssignedIds] = useState<number[]>([]);
  // Stores only the 9 digits after the locked "8801" prefix — the
  // prefix is rendered separately in the input and prepended back on
  // save, matching how the backend/DB expect the full "8801XXXXXXXXX"
  // value (V15 migration / UpdateUserRequest's @Pattern).
  const [editPhoneDigits, setEditPhoneDigits] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [newUsername, setNewUsername] = useState('');
  const [newFullName, setNewFullName] = useState('');
  const [newRole, setNewRole] = useState<'USER' | 'ADMIN' | 'SYS_ADMIN'>('USER');
  const [newEmail, setNewEmail] = useState('');
  const [newPhoneDigits, setNewPhoneDigits] = useState('');
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null); // server-side / duplicate errors
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [fullNameError, setFullNameError] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [phoneError, setPhoneError] = useState<string | null>(null);

  // Shown exactly once after a successful create — same pattern as
  // reset-password: the cleartext temp password never appears again
  // after this render, so it must be copy-able right here.
  const [createdResult, setCreatedResult] = useState<{ username: string; temporaryPassword: string } | null>(null);
  const [copied, setCopied] = useState(false);

  async function handleCopyTempPassword() {
    if (!createdResult) return;
    try {
      await navigator.clipboard.writeText(createdResult.temporaryPassword);
    } catch {
      // Fallback if the clipboard API is blocked
      const ta = document.createElement('textarea');
      ta.value = createdResult.temporaryPassword;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

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

  useEffect(() => {
    void loadUsers();
    void loadApplications();
    const interval = setInterval(() => void loadUsers(), 15000);
    return () => clearInterval(interval);
  }, []);

  function resetForm() {
    setNewUsername('');
    setNewFullName('');
    setNewRole('USER');
    setNewEmail('');
    setNewPhoneDigits('');
    setFormError(null);
    setUsernameError(null);
    setFullNameError(null);
    setEmailError(null);
    setPhoneError(null);
  }

  function openEdit(u: User) {
    setEditingUser(u);
    setEditUsername(u.username);
    setEditFullName(u.full_name ?? '');
    setEditEmail(u.email ?? '');
    // Strip the "8801" prefix back off for editing — u.phone_number
    // is either null, or the full 13-digit stored value.
    setEditPhoneDigits(u.phone_number ? u.phone_number.slice(4) : '');
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
    if (!editUsername.trim()) {
      setEditError('Username cannot be blank.');
      return;
    }
    if (!editFullName.trim()) {
      setEditError('Full name cannot be blank.');
      return;
    }
    if (!editEmail.trim()) {
      setEditError('Email cannot be blank.');
      return;
    }
    if (!EMAIL_REGEX.test(editEmail.trim())) {
      setEditError('Enter a valid email address.');
      return;
    }
    // Phone number is genuinely optional (unlike email) — blank digits
    // means "clear it" (sent as empty string, per UpdateUserRequest's
    // tri-state: empty clears, omitted/undefined leaves alone, we
    // always send a value here since the field is always rendered).
    // A non-blank value must be exactly 9 digits, matching the 13-char
    // total the backend's @Pattern expects once "8801" is prepended.
    const trimmedDigits = editPhoneDigits.trim();
    if (trimmedDigits && !/^\d{9}$/.test(trimmedDigits)) {
      setEditError('Phone number must be exactly 9 digits after 8801.');
      return;
    }
    setSavingEdit(true);
    setEditError(null);
    try {
      await api.put(API.USERS.UPDATE(editingUser.id), {
        username: editUsername.trim(),
        fullName: editFullName.trim(),
        email: editEmail.trim(),
        phoneNumber: trimmedDigits ? `8801${trimmedDigits}` : '',
        role: editRole,
        assignedApplicationIds: editAssignedIds,
      });
      setEditingUser(null);
      await loadUsers();
    } catch (err) {
      setEditError(describeApiError(err, 'Failed to update user.'));
    } finally {
      setSavingEdit(false);
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setUsernameError(null);
    setFullNameError(null);
    setEmailError(null);
    setPhoneError(null);

    let hasError = false;
    if (!newUsername.trim()) {
      setUsernameError('Username is required.');
      hasError = true;
    }
    if (!newEmail.trim()) {
      setEmailError('Email is required.');
      hasError = true;
    } else if (!EMAIL_REGEX.test(newEmail.trim())) {
      setEmailError('Enter a valid email address.');
      hasError = true;
    }
    const trimmedDigits = newPhoneDigits.trim();
    if (!/^\d{9}$/.test(trimmedDigits)) {
      setPhoneError('Phone number is required — exactly 9 digits after 8801.');
      hasError = true;
    }
    if (hasError) return;

    setCreating(true);
    try {
      const path = newRole === 'USER' ? API.USERS.CREATE : '/users/create-admin';
      const res = await api.post<{ id: number; username: string; role: string; temporaryPassword: string }>(
        path,
        {
        username: newUsername.trim(),
        role: newRole,
        email: newEmail.trim(),
        phoneNumber: `8801${trimmedDigits}`,
        assignedApplicationIds: [],
      },
      );
      setCreatedResult({ username: res.username, temporaryPassword: res.temporaryPassword });
      resetForm();
      setShowForm(false);
      await loadUsers();
    } catch (err) {
      setFormError(describeApiError(err, 'Failed to create user.'));
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

  const appNameById = new Map(applications.map((a) => [a.id, a.name]));
  const detailsUser = detailsUserId !== null ? users.find((u) => u.id === detailsUserId) ?? null : null;

  return (
    <div className="p-6 sm:p-8 max-w-6xl mx-auto">
      <PageHeader
        title="Users"
        description="Manage accounts, roles, and application assignments."
        actions={
          <Button onClick={() => { resetForm(); setShowForm(true); }}>
            + Add User
          </Button>
        }
      />

      {error && <Alert className="mb-4">{error}</Alert>}

      {createdResult && (
        <Card className="mb-6 max-w-xl border-status-online/30 bg-status-onlineBg/60 p-5 dark:border-green-500/20 dark:bg-green-500/5">
          <p className="mb-1 font-semibold text-status-online dark:text-green-400">
            User &quot;{createdResult.username}&quot; created.
          </p>
          <p className="mb-2 text-sm text-slate-600 dark:text-gh-fgSoft">
            Temporary password (shown once — deliver this out-of-band; it cannot be retrieved again):
          </p>
          <code className="block break-all rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-sm dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg">
            {createdResult.temporaryPassword}
          </code>
          <div className="mt-3 flex gap-2">
            <Button size="sm" onClick={() => void handleCopyTempPassword()}>
              {copied ? 'Copied ✓' : 'Copy password'}
            </Button>
            <Button size="sm" variant="secondary" onClick={() => { setCreatedResult(null); setCopied(false); }}>
              Dismiss
            </Button>
          </div>
        </Card>
      )}

      {showForm && (
        <Modal onClose={() => { setShowForm(false); resetForm(); }}>
          <form onSubmit={handleCreate} noValidate>
            <div className="border-b border-slate-200 bg-slate-50 px-5 py-3 dark:border-gh-border dark:bg-gh-subtle/60">
              <h2 className="text-base font-semibold text-slate-900 dark:text-white">New User</h2>
            </div>
            <div className="bg-slate-100 p-5 dark:bg-gh-inset">
            <label className="mb-3 block">
              <span className={labelClass}>Full name</span>
              <input
                value={newFullName}
                onChange={(e) => setNewFullName(e.target.value)}
                className={inputClass}
                required
              />
              {fullNameError && (
                <span className="mt-1 block text-xs text-red-500">{fullNameError}</span>
              )}
            </label>
            <label className="mb-3 block">
              <span className={labelClass}>Username</span>
              <input
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                className={inputClass}
                required
              />
              {usernameError && (
                <span className="mt-1 block text-xs text-red-500">{usernameError}</span>
              )}
            </label>
            <label className="mb-3 block">
              <span className={labelClass}>Email</span>
              <input
                type="email"
                value={newEmail}
                onChange={(e) => setNewEmail(e.target.value)}
                className={inputClass}
                required
              />
              {emailError && (
                <span className="mt-1 block text-xs text-red-500">{emailError}</span>
              )}
            </label>

            <label className="mb-3 block">
              <span className={labelClass}>Phone number</span>
              <div className="flex items-center gap-2">
                <span className="rounded-lg border border-slate-300 bg-slate-50 px-3 py-2 text-sm text-slate-500 dark:border-gh-border dark:bg-gh-hover dark:text-gh-muted">
                  8801
                </span>
                <input
                  type="tel"
                  inputMode="numeric"
                  value={newPhoneDigits}
                  onChange={(e) => setNewPhoneDigits(e.target.value.replace(/\D/g, '').slice(0, 9))}
                  placeholder="XXXXXXXXX"
                  maxLength={9}
                  className={inputClass}
                  required
                />
              </div>
              {phoneError && (
                <span className="mt-1 block text-xs text-red-500">{phoneError}</span>
              )}
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
                <span className="mt-1 block text-xs text-slate-500 dark:text-gh-muted">
                  Only Sys.Admin can create Admin accounts.
                </span>
              )}
            </label>
            {formError && <Alert className="mb-4">{formError}</Alert>}
            <Button type="submit" loading={creating}>
              {creating ? 'Creating…' : 'Create User'}
            </Button>
            </div>
          </form>
        </Modal>
      )}

      {loading ? (
        <LoadingBlock className="py-10" />
      ) : users.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500 dark:text-gh-muted">No users yet.</Card>
      ) : (
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[44rem] text-sm">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500 dark:border-gh-border dark:bg-gh-subtle/60 dark:text-gh-muted">
                <th className="px-4 py-3 font-medium">Username</th>
                <th className="px-4 py-3 font-medium">Applications</th>
                <th className="px-4 py-3 font-medium">Role</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Created</th>
                <th className="px-4 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="bg-slate-100 dark:bg-gh-inset">
              {users.map((u) => {
                const isSelf = currentUser?.id === u.id;
                const isSysAdminRow = u.role === 'SYS_ADMIN';
                const viewerIsSysAdmin = currentUser?.role === 'SYS_ADMIN';
                const canManageRow = viewerIsSysAdmin || !isSysAdminRow;
                return (
                  <tr key={u.id} className="border-b border-slate-200 last:border-0 dark:border-gh-border">
                    <td className="px-4 py-3 font-medium text-slate-900 dark:text-white">
                      {u.username}
                      {isSelf && <span className="ml-2 text-xs font-normal text-slate-400">(you)</span>}
                    </td>
                    <td className="px-4 py-3">
                      {u.role !== 'USER' ? (
                        <span className="text-xs text-slate-500 dark:text-gh-muted">All applications</span>
                      ) : (u.assignedApplicationIds ?? []).length === 0 ? (
                        <span className="text-xs italic text-slate-400">None assigned</span>
                      ) : (
                        <div className="flex flex-wrap gap-1">
                          {u.assignedApplicationIds.map((appId) => (
                            <Badge key={appId} tone="neutral" dot={false}>
                              {appNameById.get(appId) ?? `#${appId}`}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={roleTone(u.role)} dot={false}>{roleLabel(u.role)}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      {u.must_change_password ? (
                        <Badge tone="pending">Must change password</Badge>
                      ) : (
                        <Badge tone={u.online ? 'online' : 'offline'}>
                          {u.online ? 'Online' : 'Offline'}
                        </Badge>
                      )}
                    </td>
                    <td className="px-4 py-3 text-slate-500 dark:text-gh-muted">
                      {new Date(u.created_at).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <Button size="sm" variant="secondary" onClick={() => setDetailsUserId(u.id)}>
                          Details
                        </Button>
                        {canManageRow && (
                          <>
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
                              loading={deletingId === u.id}
                              disabled={isSelf}
                              onClick={() => handleDelete(u)}
                              title={isSelf ? 'Cannot delete your own account' : undefined}
                            >
                              Delete
                            </Button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}

      {detailsUser && (
        <Modal onClose={() => setDetailsUserId(null)} widthClass="max-w-lg">
          <div className="border-b border-slate-200 bg-slate-50 px-5 py-3 dark:border-gh-border dark:bg-gh-subtle/60">
            <h2 className="text-base font-semibold text-slate-900 dark:text-white">User details</h2>
          </div>
          <div className="bg-slate-100 p-5 dark:bg-gh-inset">
            <dl className="divide-y divide-slate-200 text-sm dark:divide-gh-border">
              <DetailRow label="Full name">
                {detailsUser.full_name ?? <span className="font-normal italic text-slate-400">Not set</span>}
              </DetailRow>
              <DetailRow label="Username">{detailsUser.username}</DetailRow>
              <DetailRow label="Email">
                {detailsUser.email ?? <span className="font-normal italic text-slate-400">none</span>}
              </DetailRow>
              <DetailRow label="Phone">
                {detailsUser.phone_number ? (
                  <span className="font-mono text-xs">{detailsUser.phone_number}</span>
                ) : (
                  <span className="font-normal italic text-slate-400">none</span>
                )}
              </DetailRow>
              <DetailRow label="Role">
                <Badge tone={roleTone(detailsUser.role)} dot={false}>{roleLabel(detailsUser.role)}</Badge>
              </DetailRow>
              <DetailRow label="Status">
                <span className="inline-flex flex-wrap justify-end gap-1.5">
                  <Badge tone={detailsUser.online ? 'online' : 'offline'}>
                    {detailsUser.online ? 'Online' : 'Offline'}
                  </Badge>
                  {detailsUser.must_change_password && <Badge tone="pending">Must change password</Badge>}
                </span>
              </DetailRow>
              <DetailRow label="Created">{new Date(detailsUser.created_at).toLocaleString()}</DetailRow>
              <DetailRow label="Applications">
                {detailsUser.role !== 'USER' ? (
                  <span className="font-normal">All applications</span>
                ) : (detailsUser.assignedApplicationIds ?? []).length === 0 ? (
                  <span className="font-normal italic text-slate-400">None assigned</span>
                ) : (
                  <span className="inline-flex flex-wrap justify-end gap-1">
                    {detailsUser.assignedApplicationIds.map((appId) => (
                      <Badge key={appId} tone="neutral" dot={false}>
                        {appNameById.get(appId) ?? `#${appId}`}
                      </Badge>
                    ))}
                  </span>
                )}
              </DetailRow>
            </dl>
            <div className="mt-5 flex justify-end">
              <Button variant="secondary" onClick={() => setDetailsUserId(null)}>Close</Button>
            </div>
          </div>
        </Modal>
      )}

      {editingUser && (
        <Modal onClose={() => setEditingUser(null)}>
          <div className="border-b border-slate-200 bg-slate-50 px-5 py-3 dark:border-gh-border dark:bg-gh-subtle/60">
            <h2 className="text-base font-semibold text-slate-900 dark:text-white">
              Edit &quot;{editingUser.username}&quot;
            </h2>
          </div>
          <div className="bg-slate-100 p-5 dark:bg-gh-inset">

          <label className="mb-3 block">
            <span className={labelClass}>Full name</span>
            <input
              value={editFullName}
              onChange={(e) => setEditFullName(e.target.value)}
              className={inputClass}
              required
            />
          </label>
          <label className="mb-3 block">
            <span className={labelClass}>Username</span>
            <input
              value={editUsername}
              onChange={(e) => setEditUsername(e.target.value)}
              className={inputClass}
              required
            />
          </label>

          <label className="mb-3 block">
            <span className={labelClass}>Email</span>
            <input
              type="email"
              value={editEmail}
              onChange={(e) => setEditEmail(e.target.value)}
              className={inputClass}
              required
            />
          </label>

          <label className="mb-3 block">
            <span className={labelClass}>Phone number (optional — for SMS alerts)</span>
            <div className="flex items-center gap-2">
              <span className="rounded-lg border border-slate-300 bg-slate-50 px-3 py-2 text-sm text-slate-500 dark:border-gh-border dark:bg-gh-hover dark:text-gh-muted">
                8801
              </span>
              <input
                type="tel"
                inputMode="numeric"
                value={editPhoneDigits}
                onChange={(e) => setEditPhoneDigits(e.target.value.replace(/\D/g, '').slice(0, 9))}
                placeholder="XXXXXXXXX"
                maxLength={9}
                className={inputClass}
              />
            </div>
          </label>

          <label className="mb-4 block">
            <span className={labelClass}>Role</span>
            <select
              value={editRole}
              onChange={(e) => setEditRole(e.target.value as 'USER' | 'ADMIN' | 'SYS_ADMIN')}
              className={inputClass}
              disabled={editingUser.role === 'SYS_ADMIN' && !canCreateAdmin}
            >
              <option value="USER">User</option>
              {canCreateAdmin && <option value="ADMIN">Admin</option>}
              {canCreateAdmin && <option value="SYS_ADMIN">Sys.Admin</option>}
            </select>
            {!canCreateAdmin && (
              <span className="mt-1 block text-xs text-slate-500 dark:text-gh-muted">
                Only Sys.Admin can change roles to/from Admin or Sys.Admin.
              </span>
            )}
          </label>

          {editRole === 'USER' && (
            <fieldset className="mb-4">
              <legend className={labelClass}>Assigned Applications</legend>
              {applications.length === 0 ? (
                <p className="text-xs text-slate-500 dark:text-gh-muted">No applications available.</p>
              ) : (
                <>
                  <div className="flex flex-wrap gap-2">
                    {applications.map((app) => {
                      const selected = editAssignedIds.includes(app.id);
                      return (
                        <button
                          key={app.id}
                          type="button"
                          role="checkbox"
                          aria-checked={selected}
                          onClick={() => toggleAssigned(app.id)}
                          className={[
                            'rounded-full border px-3.5 py-1 text-sm transition-colors duration-150',
                            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
                            selected
                              ? 'border-brand-200 bg-brand-50 text-brand-700 dark:border-brand-500/40 dark:bg-brand-500/15 dark:text-brand-300'
                              : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50 dark:border-gh-border dark:bg-surface-darkSubtle dark:text-gh-muted dark:hover:bg-gh-hover',
                          ].join(' ')}
                        >
                          {app.name}
                        </button>
                      );
                    })}
                  </div>
                  <p className="mt-2 text-xs text-slate-500 dark:text-gh-muted">
                    Click an application to assign it, click again to remove it.
                  </p>
                </>
              )}
            </fieldset>
          )}

          {editRole !== 'USER' && (
            <p className="mb-4 text-xs text-slate-500 dark:text-gh-muted">
              Admin and Sys.Admin see all applications — no assignment needed.
            </p>
          )}

          {editError && <Alert className="mb-4">{editError}</Alert>}

          <div className="flex gap-2">
            <Button onClick={handleSaveEdit} loading={savingEdit}>
              {savingEdit ? 'Saving…' : 'Save'}
            </Button>
            <Button variant="secondary" onClick={() => setEditingUser(null)}>
              Cancel
            </Button>
          </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
