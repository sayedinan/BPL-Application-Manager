import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Link, Route, Routes, Navigate } from 'react-router-dom';
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
import { RequireAuth } from '@/app/RequireAuth';
import { Shell } from '@/app/Shell';
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
        className="mt-5 rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white transition-theme hover:bg-brand-700"
      >
        Back to dashboard
      </Link>
    </div>
  );
}

function SysAdminOnly({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  return user?.role === 'SYS_ADMIN' ? <>{children}</> : <ForbiddenPage />;
}
function AdminPlus({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  return user?.role === 'SYS_ADMIN' || user?.role === 'ADMIN' ? <>{children}</> : <ForbiddenPage />;
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
              <Shell>
                <AppRoutes />
              </Shell>
            </ToastProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
