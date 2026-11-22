import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Alert } from '@/components/ui/Alert';

interface ChangePasswordResponse {
  id: string;
  username: string;
  role: string;
  mustChangePassword: boolean;
}

const inputClass =
  'mt-1 block w-full rounded-xl border border-rule bg-panel shadow-sm px-3 py-2 text-sm text-ink ' +
  'transition-theme placeholder:text-muted/70 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  ' dark:bg-sunken dark:text-white';

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
      className="relative flex min-h-screen items-center justify-center overflow-hidden bg-canvas px-4 dark:bg-sunken"
    >

      <Card className="relative w-full max-w-sm animate-fade-in p-8 shadow-popover">
        <div className="mb-6 text-center">
          <h1 className="text-xl font-bold text-ink dark:text-white">Change Password</h1>
          <p className="mt-1 text-sm text-muted">
            {user?.mustChangePassword
              ? 'You must set a new password before continuing.'
              : 'Enter your current password, then choose a new one.'}
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="oldPassword" className="block text-sm font-medium text-ink/80">
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
            <label htmlFor="newPassword" className="block text-sm font-medium text-ink/80">
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
            <label htmlFor="confirmPassword" className="block text-sm font-medium text-ink/80">
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

          <Button type="submit" loading={submitting} className="w-full">
            {submitting ? 'Saving…' : 'Save new password'}
          </Button>
        </form>
      </Card>
    </div>
  );
}