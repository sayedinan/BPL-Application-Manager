import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes, Navigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

import { AuthHydrator } from '@/app/AuthHydrator';
import { AuthProvider } from '@/auth/AuthContext';
import { ThemeProvider } from '@/lib/theme';
import { LoginPage } from '@/auth/LoginPage';
import { RealChangePasswordPage } from '@/auth/ChangePasswordPage';
import { DashboardPlaceholder } from '@/app/Dashboard';
import { ApplicationsPage } from '@/app/ApplicationsPage';
import { UsersPage } from '@/app/UsersPage';
import { LogsPage } from '@/app/LogsPage';
import { PresenceConnection } from '@/app/PresenceConnection';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { Logo } from '@/components/ui/Logo';
import { ThemeToggle } from '@/components/ui/ThemeToggle';

const queryClient = new QueryClient();

function ForbiddenPage() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center text-center px-4">
      <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-red-50 text-red-500 dark:bg-red-500/10">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="4.9" y1="4.9" x2="19.1" y2="19.1" />
        </svg>
      </div>
      <h1 className="text-xl font-bold text-slate-900 dark:text-white">403 — Access Denied</h1>
      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">You don't have access to this page.</p>
    </div>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'unknown') {
    return (
      <div className="flex min-h-[70vh] items-center justify-center text-sm text-slate-500 dark:text-slate-400">
        Loading…
      </div>
    );
  }
  if (status !== 'authenticated') return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function SysAdminOnly({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  return user?.role === 'SYS_ADMIN' ? <>{children}</> : <ForbiddenPage />;
}
function AdminPlus({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  return user?.role === 'SYS_ADMIN' || user?.role === 'ADMIN' ? <>{children}</> : <ForbiddenPage />;
}


function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  const active = window.location.pathname === href;
  return (
    <a
      href={href}
      className={[
        'whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium transition-theme',
        active
          ? 'bg-brand-50 text-brand-700 dark:bg-brand-500/10 dark:text-brand-300'
          : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white',
      ].join(' ')}
    >
      {children}
    </a>
  );
}

function NavBar() {
  const { status, user, setUser, setStatus } = useAuth();
  const location = window.location.pathname;
  if (status !== 'authenticated' || !user || location === '/login') return null;
  if (user.mustChangePassword && location !== '/change-password') return null;

  async function handleLogout() {
    try {
      await api.post(API.AUTH.LOGOUT, undefined, { skipAuthRedirect: true });
    } finally {
      setUser(null);
      setStatus('unauthenticated');
      window.location.assign('/login');
    }
  }

  return (
    <nav className="sticky top-0 z-20 flex flex-wrap items-center gap-x-1 gap-y-1 border-b border-slate-200 bg-white/80 px-3 py-2 sm:px-6 sm:py-3 backdrop-blur transition-theme dark:border-slate-800 dark:bg-surface-dark/80">
      <a href="/" className="mr-4 flex items-center gap-2">
        <Logo className="h-8 w-8" />
        <span className="hidden text-sm font-bold tracking-tight text-slate-900 sm:inline dark:text-white">
          BPL Admin
        </span>
      </a>

      <NavLink href="/">Dashboard</NavLink>
      {user.role === 'SYS_ADMIN' && <NavLink href="/applications">Applications</NavLink>}
      {(user.role === 'SYS_ADMIN' || user.role === 'ADMIN') && <NavLink href="/users">Users</NavLink>}
      {(user.role === 'SYS_ADMIN' || user.role === 'ADMIN') && <NavLink href="/logs">Logs</NavLink>}

      <div className="ml-auto flex flex-wrap items-center gap-1 sm:gap-3">
        <ThemeToggle />
        <div className="hidden text-right sm:block">
          <p className="text-sm font-medium leading-tight text-slate-900 dark:text-white">{user.username}</p>
          <p className="text-xs leading-tight text-slate-500 dark:text-slate-400">{user.role}</p>
        </div>
        <NavLink href="/change-password">Change password</NavLink>
        <button
          onClick={handleLogout}
          className="whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 transition-theme dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
        >
          Log out
        </button>
      </div>
    </nav>
  );
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<RequireAuth><DashboardPlaceholder /></RequireAuth>} />
      <Route path="/applications" element={<RequireAuth><SysAdminOnly><ApplicationsPage /></SysAdminOnly></RequireAuth>} />
      <Route path="/users" element={<RequireAuth><AdminPlus><UsersPage /></AdminPlus></RequireAuth>} />
      <Route path="/logs" element={<RequireAuth><AdminPlus><LogsPage /></AdminPlus></RequireAuth>} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/change-password" element={<RequireAuth><RealChangePasswordPage /></RequireAuth>} />
      <Route path="/force-change-password" element={<RequireAuth><Navigate to="/change-password" /></RequireAuth>} />
      <Route path="*" element={<Navigate to="/" />} />
    </Routes>
  );
}

export function App(): JSX.Element {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <AuthHydrator />
            <PresenceConnection />
            <div className="min-h-screen bg-surface-subtle transition-theme dark:bg-surface-dark">
              <NavBar />
              <AppRoutes />
            </div>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
