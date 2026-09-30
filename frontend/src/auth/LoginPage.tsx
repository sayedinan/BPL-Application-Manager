import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth, type CurrentUser } from '@/auth/AuthContext';
import { Button } from '@/components/ui/Button';
import { Alert } from '@/components/ui/Alert';
import { Logo } from '@/components/ui/Logo';
import { ThemeToggle } from '@/components/ui/ThemeToggle';

interface LoginResponse {
  id: number;
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
        body: { username: username.trim(), password },
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
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-surface-subtle px-4 dark:bg-surface-dark">
      {/* Ambient brand glow (light mode only) */}
      <div className="pointer-events-none absolute -top-32 left-1/2 h-96 w-96 -translate-x-1/2 rounded-full bg-brand-500/20 blur-3xl dark:hidden" />

      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>

      {/* Light: card. Dark: no card, form sits directly on the page like GitHub */}
      <div className="relative w-full max-w-sm animate-fade-in rounded-2xl border border-slate-200 bg-white p-8 shadow-popover dark:border-transparent dark:bg-transparent dark:shadow-none">
        <div className="mb-6 flex flex-col items-center text-center">
          <Logo className="mb-4 h-14 w-14" />
          <h1 className="text-xl font-semibold text-slate-900 dark:text-gh-fg">Sign in to BPL Admin</h1>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="username" className="block text-sm font-semibold text-slate-700 dark:text-gh-fg">
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
              className="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 transition-theme placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 dark:border-gh-border dark:bg-gh-inset dark:text-gh-fg"
            />
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-semibold text-slate-700 dark:text-gh-fg">
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
              className="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 transition-theme placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 dark:border-gh-border dark:bg-gh-inset dark:text-gh-fg"
            />
          </div>

          {error && <Alert>{error}</Alert>}

          <Button
            type="submit"
            disabled={submitting}
            className="w-full dark:!bg-[#238636] dark:hover:!bg-[#2ea043]"
          >
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </div>
    </div>
  );
}
