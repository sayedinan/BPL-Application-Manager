import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth, type CurrentUser } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Alert } from '@/components/ui/Alert';
import { CONTROL } from '@/components/ui/Field';
import { AuthLayout } from '@/auth/AuthLayout';

interface LoginResponse {
  id: string;
  username: string;
  role: CurrentUser['role'];
  mustChangePassword: boolean;
}

interface MeResponse extends LoginResponse {
  assignedApplicationIds: number[];
}

export function LoginPage(): JSX.Element {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const { setUser, setStatus } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!username.trim() || !password) {
      setError('Username and password are required.');
      return;
    }

    setSubmitting(true);
    try {
      const loginResult = await apiFetch<LoginResponse>(API.AUTH.LOGIN, {
        method: 'POST',
        body: { username: username.trim(), password, rememberMe },
        skipAuthRedirect: true,
      });

      const me = await apiFetch<MeResponse>(API.AUTH.ME);
      setUser(me);
      setStatus('authenticated');

      navigate(loginResult.mustChangePassword ? '/change-password' : '/', {
        replace: true,
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Incorrect username or password.');
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-1 text-2xl font-bold tracking-tight text-ink">Sign in to BPL Admin</h1>
      <p className="mb-6 text-sm text-muted">Use your account username and password.</p>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="username" className="block text-sm font-semibold text-ink">
              Username
            </label>
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={submitting}
              className={`mt-1 ${CONTROL}`}
            />
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-semibold text-ink">
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={submitting}
              className={`mt-1 ${CONTROL}`}
            />
          </div>

          <label className="flex min-h-6 items-center gap-2 text-sm text-ink">
            <input
              type="checkbox"
              checked={rememberMe}
              onChange={(e) => setRememberMe(e.target.checked)}
              disabled={submitting}
              className="h-4 w-4 rounded border-field accent-brand-600"
            />
            Remember me
          </label>

          {error && <Alert>{error}</Alert>}

          <Button type="submit" loading={submitting} className="w-full">
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
    </AuthLayout>
  );
}
