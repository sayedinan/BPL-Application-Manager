import { useEffect, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';

interface User {
  id: number;
  username: string;
  role: 'SYS_ADMIN' | 'ADMIN' | 'OPERATOR';
  must_change_password: boolean;
  created_at: string;
}

export function UsersPage(): JSX.Element {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);

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

  useEffect(() => { void loadUsers(); }, []);

  async function handleDelete(user: User) {
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
      <h1 className="text-2xl font-semibold mb-6">Users</h1>
      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}
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
            {users.map((user) => (
              <tr key={user.id} className="border-b">
                <td className="py-2">{user.username}</td>
                <td className="py-2">
                  <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                    user.role === 'SYS_ADMIN' ? 'bg-red-100 text-red-800' :
                    user.role === 'ADMIN' ? 'bg-blue-100 text-blue-800' :
                    'bg-gray-100 text-gray-800'
                  }`}>
                    {user.role}
                  </span>
                </td>
                <td className="py-2">
                  {user.must_change_password && (
                    <span className="text-xs text-orange-600">⚠ Must change password</span>
                  )}
                </td>
                <td className="py-2 text-gray-600">{new Date(user.created_at).toLocaleDateString()}</td>
                <td className="py-2 text-right">
                  <button disabled={deletingId === user.id} onClick={() => handleDelete(user)} className="text-xs px-3 py-1 bg-red-600 text-white rounded disabled:opacity-40">
                    {deletingId === user.id ? 'Deleting…' : 'Delete'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
