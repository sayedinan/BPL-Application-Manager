import { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Link, Route, Routes, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

import { AuthHydrator } from '@/app/AuthHydrator';
import { AuthProvider } from '@/auth/AuthContext';
import { ThemeProvider } from '@/lib/theme';
import { LoginPage } from '@/auth/LoginPage';
import { RealChangePasswordPage } from '@/auth/ChangePasswordPage';
import { DashboardPlaceholder } from '@/app/Dashboard';
import { ApplicationsPage } from '@/app/ApplicationsPage';
import { ApplicationDetailsPage } from '@/app/ApplicationDetailsPage';
import { UsersPage } from '@/app/UsersPage';
import { LogsPage } from '@/app/LogsPage';
import { PresenceConnection } from '@/app/PresenceConnection';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { Logo } from '@/components/ui/Logo';
import { ThemeToggle } from '@/components/ui/ThemeToggle';
import { UserMenu } from '@/components/ui/UserMenu';
import { LoadingBlock } from '@/components/ui/Spinner';
import { ToastProvider } from '@/components/ui/Toast';

const queryClient = new QueryClient();

function ForbiddenPage() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center text-center px-4">
      <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-xl bg-danger-bg text-danger">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <line x1="4.9" y1="4.9" x2="19.1" y2="19.1" />
        </svg>
      </div>
      <h1 className="text-xl font-semibold text-ink">403 — Access Denied</h1>
      <p className="mt-1 max-w-sm text-sm text-muted">
        You don't have access to this page. If you think you should, ask an admin to update your access.
      </p>
      <Link
        to="/"
        className="mt-5 rounded-lg border border-rule bg-panel px-4 py-2 text-sm font-medium text-ink transition-theme hover:bg-sunken"
      >
        Back to dashboard
      </Link>
    </div>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'unknown') {
    return (
      <LoadingBlock className="min-h-[70vh]" />
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


function NavLink({ to, children }: { to: string; children: React.ReactNode }) {
  // The application details pages (/apps/:name) belong to the Dashboard tab.
  const { pathname } = useLocation();
  const active = pathname === to || (to === '/' && pathname.startsWith('/apps/'));
  return (
    <Link
      to={to}
      aria-current={active ? 'page' : undefined}
      className={[
        // flex + items-center: the link stretches to the header's full height (so the
        // active underline sits on the header's bottom edge) while its label stays centred.
        'flex items-center whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium transition-theme',
        'max-md:rounded-lg max-md:border-b-0 max-md:py-2.5',
        active
          ? 'border-accent text-ink max-md:bg-sunken'
          : 'border-transparent text-muted hover:text-ink max-md:hover:bg-sunken',
      ].join(' ')}
    >
      {children}
    </Link>
  );
}

function NavBar() {
  const { status, user, setUser, setStatus } = useAuth();
  const { pathname } = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);

  // Close the mobile menu after navigating.
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  if (status !== 'authenticated' || !user || pathname === '/login') return null;
  if (user.mustChangePassword && pathname !== '/change-password') return null;

  async function handleLogout() {
    try {
      await api.post(API.AUTH.LOGOUT, undefined, { skipAuthRedirect: true });
    } finally {
      setUser(null);
      setStatus('unauthenticated');
      // Deliberate full reload: tears down the session state and WebSocket cleanly.
      window.location.assign('/login');
    }
  }

  const isAdminPlus = user.role === 'SYS_ADMIN' || user.role === 'ADMIN';

  const links = (
    <>
      <NavLink to="/">Dashboard</NavLink>
      {user.role === 'SYS_ADMIN' && <NavLink to="/applications">Applications</NavLink>}
      {isAdminPlus && <NavLink to="/users">Users</NavLink>}
      {isAdminPlus && <NavLink to="/logs">Logs</NavLink>}
    </>
  );

  return (
    <header className="sticky top-0 z-20 border-b border-rule bg-panel transition-theme">
      {/* Same column as the page content (max-w-6xl, px-4/sm:px-8) so the logo lines up with titles. */}
      <div className="mx-auto flex max-w-6xl items-center gap-1 px-4 sm:px-8">
        <Link to="/" className="mr-4 flex items-center gap-2 py-2" aria-label="BPL Admin home">
          <Logo className="h-8 w-8" />
          <span className="hidden text-sm font-semibold tracking-tight text-ink sm:inline">BPL Admin</span>
        </Link>

        <nav aria-label="Main" className="hidden items-stretch self-stretch md:flex">
          {links}
        </nav>

        <div className="ml-auto flex items-center gap-1 py-2 sm:gap-3">
          <ThemeToggle />
          <UserMenu
            username={user.username}
            role={user.role}
            email={user.email}
            phoneNumber={user.phoneNumber}
            onLogout={handleLogout}
          />
          <button
            type="button"
            onClick={() => setMenuOpen((o) => !o)}
            aria-expanded={menuOpen}
            aria-controls="mobile-nav"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-muted hover:bg-sunken hover:text-ink md:hidden"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              {menuOpen ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
            </svg>
          </button>
        </div>
      </div>

      {menuOpen && (
        <nav id="mobile-nav" aria-label="Main" className="flex flex-col gap-1 border-t border-rule p-2 md:hidden">
          {links}
        </nav>
      )}
    </header>
  );
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<RequireAuth><DashboardPlaceholder /></RequireAuth>} />
      <Route path="/apps/:name" element={<RequireAuth><ApplicationDetailsPage /></RequireAuth>} />
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
            <ToastProvider>
              <div className="min-h-screen bg-canvas transition-theme">
                <NavBar />
                <AppRoutes />
              </div>
            </ToastProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
