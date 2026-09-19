import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Alert } from '@/components/ui/Alert';

interface ChangePasswordResponse {
  id: number;
  username: string;
  role: string;
  mustChangePassword: boolean;
}

const inputClass =
  'mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 ' +
  'transition-theme placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-slate-700 dark:bg-surface-dark dark:text-white';

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
    if (newPassword.length < 8) { setError('New password must be at least 8 characters.'); return; }
    if (!/[a-z]/.test(newPassword) || !/[A-Z]/.test(newPassword) || !/\d/.test(newPassword) || !/[^A-Za-z0-9]/.test(newPassword)) {
      setError('New password must include an uppercase letter, a lowercase letter, a number, and a symbol.');
      return;
    }
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
    <div
      data-change-password-page="real"
      className="relative flex min-h-screen items-center justify-center overflow-hidden bg-surface-subtle px-4 dark:bg-surface-dark"
    >
      <div className="pointer-events-none absolute -top-32 left-1/2 h-96 w-96 -translate-x-1/2 rounded-full bg-brand-500/20 blur-3xl" />

      <Card className="relative w-full max-w-sm animate-fade-in p-8 shadow-popover">
        <div className="mb-6 text-center">
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">Change Password</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            You must set a new password before continuing.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="oldPassword" className="block text-sm font-medium text-slate-700 dark:text-slate-300">
              Current password
            </label>
            <input
              id="oldPassword"
              type="password"
              autoComplete="current-password"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
              className={inputClass}
              required
              autoFocus
            />
          </div>

          <div>
            <label htmlFor="newPassword" className="block text-sm font-medium text-slate-700 dark:text-slate-300">
              New password
            </label>
            <input
              id="newPassword"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className={inputClass}
              minLength={8}
              required
            />
          </div>

          <div>
            <label htmlFor="confirmPassword" className="block text-sm font-medium text-slate-700 dark:text-slate-300">
              Confirm new password
            </label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className={inputClass}
              minLength={8}
              required
            />
          </div>

          {error && <Alert>{error}</Alert>}

          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? 'Saving…' : 'Save new password'}
          </Button>
        </form>
      </Card>
    </div>
  );
}