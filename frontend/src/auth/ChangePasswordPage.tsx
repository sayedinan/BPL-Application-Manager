import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';

interface ChangePasswordResponse {
  id: number;
  username: string;
  role: string;
  mustChangePassword: boolean;
}

export function RealChangePasswordPage(): JSX.Element {
  const { user, setUser } = useAuth();
  const navigate = useNavigate();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (newPassword.length < 12) { setError('New password must be at least 12 characters.'); return; }
    if (newPassword !== confirmPassword) { setError('New password and confirmation do not match.'); return; }
    if (newPassword === oldPassword) { setError('New password must differ from the current password.'); return; }
    if (!user) { setError('Your session could not be verified. Please sign in again.'); return; }
    setSubmitting(true);
    try {
      const result = await api.post<ChangePasswordResponse>(
        API.AUTH.CHANGE_PASSWORD,
        { username: user.username, oldPassword, newPassword },
        { skipAuthRedirect: true },
      );
      setUser({ ...user, mustChangePassword: result.mustChangePassword });
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.code === 'INVALID_CREDENTIALS' ? 'Current password is incorrect.' : err.message);
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div data-change-password-page="real" className="p-8 max-w-sm mx-auto mt-20">
      <h1 className="text-2xl font-semibold mb-2">Change Password</h1>
      <p className="text-sm text-gray-600 mb-6">You must set a new password before continuing.</p>
      <form onSubmit={handleSubmit} noValidate>
        <label className="block mb-3">
          <span className="block text-sm text-gray-700 mb-1">Current password</span>
          <input type="password" autoComplete="current-password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} className="w-full border rounded px-3 py-2" required autoFocus />
        </label>
        <label className="block mb-3">
          <span className="block text-sm text-gray-700 mb-1">New password</span>
          <input type="password" autoComplete="new-password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} className="w-full border rounded px-3 py-2" minLength={12} required />
        </label>
        <label className="block mb-4">
          <span className="block text-sm text-gray-700 mb-1">Confirm new password</span>
          <input type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} className="w-full border rounded px-3 py-2" minLength={12} required />
        </label>
        {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}
        <button type="submit" disabled={submitting} className="w-full bg-blue-600 text-white rounded px-3 py-2 disabled:opacity-50">
          {submitting ? 'Saving…' : 'Save new password'}
        </button>
      </form>
    </div>
  );
}
