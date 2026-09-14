import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes, Navigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

import { AuthHydrator } from '@/app/AuthHydrator';
import { AuthProvider } from '@/auth/AuthContext';
import { ThemeProvider, useTheme } from '@/lib/theme';
import { LoginPage } from '@/auth/LoginPage';
import { RealChangePasswordPage } from '@/auth/ChangePasswordPage';
import { DashboardPlaceholder } from '@/app/Dashboard';
import { ApplicationsPage } from '@/app/ApplicationsPage';
import { CreateAdminPage } from '@/app/CreateAdminPage';
import { EnginesPage } from '@/app/EnginesPage';
import { UsersPage } from '@/app/UsersPage';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';

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

function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  return (
    <button
      onClick={toggleTheme}
      aria-label="Toggle dark mode"
      className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700 transition-theme dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200"
    >
      {theme === 'dark' ? (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
        </svg>
      ) : (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
        </svg>
      )}
    </button>
  );
}

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  const active = window.location.pathname === href;
  return (
    <a
      href={href}
      className={[
        'rounded-lg px-3 py-1.5 text-sm font-medium transition-theme',
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
    <nav className="sticky top-0 z-20 flex items-center gap-1 border-b border-slate-200 bg-white/80 px-6 py-3 backdrop-blur transition-theme dark:border-slate-800 dark:bg-surface-dark/80">
      <a href="/" className="mr-4 flex items-center gap-2">
        <img src="/logo.png" alt="BPL" className="h-8 w-8" />
        <span className="hidden text-sm font-bold tracking-tight text-slate-900 sm:inline dark:text-white">
          BPL Admin
        </span>
      </a>

      <NavLink href="/">Dashboard</NavLink>
      {user.role === 'SYS_ADMIN' && <NavLink href="/applications">Applications</NavLink>}
      {(user.role === 'SYS_ADMIN' || user.role === 'ADMIN') && <NavLink href="/users">Users</NavLink>}

      <div className="ml-auto flex items-center gap-3">
        <ThemeToggle />
        <div className="hidden text-right sm:block">
          <p className="text-sm font-medium leading-tight text-slate-900 dark:text-white">{user.username}</p>
          <p className="text-xs leading-tight text-slate-500 dark:text-slate-400">{user.role}</p>
        </div>
        <button
          onClick={handleLogout}
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 transition-theme dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
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
      <Route path="/users/create-admin" element={<RequireAuth><SysAdminOnly><CreateAdminPage /></SysAdminOnly></RequireAuth>} />
      <Route path="/engines" element={<RequireAuth><SysAdminOnly><EnginesPage /></SysAdminOnly></RequireAuth>} />
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
