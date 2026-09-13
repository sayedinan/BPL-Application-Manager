import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes, Navigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

import { AuthHydrator } from '@/app/AuthHydrator';
import { AuthProvider } from '@/auth/AuthContext';
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

function ForbiddenPage() { return <div style={{padding:40,textAlign:'center'}}><h1>403 — Access Denied</h1><p>You don't have access to this page.</p></div>; }

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'unknown') return <div style={{ padding: 40, textAlign: 'center' }}>Loading…</div>;
  if (status !== 'authenticated') return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function SysAdminOnly({children}:{children:React.ReactNode}) { const {user}=useAuth(); return user?.role==="SYS_ADMIN"?<>{children}</>:<ForbiddenPage />; }
function AdminPlus({children}:{children:React.ReactNode}) { const {user}=useAuth(); return (user?.role==="SYS_ADMIN"||user?.role==="ADMIN")?<>{children}</>:<ForbiddenPage />; }

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
    <nav style={{ display: 'flex', gap: 16, alignItems: 'center', padding: '12px 24px', borderBottom: '1px solid #e5e7eb' }}>
      <a href="/">Dashboard</a>
      {user.role === 'SYS_ADMIN' && <a href="/applications">Applications</a>}
      {(user.role === 'SYS_ADMIN' || user.role === 'ADMIN') && <a href="/users">Users</a>}
      <span style={{ marginLeft: 'auto', fontSize: 14, color: '#6b7280' }}>{user.username} ({user.role})</span>
      <button onClick={handleLogout} style={{ fontSize: 14 }}>Log out</button>
    </nav>
  );
}

export function App(): JSX.Element {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <AuthHydrator />
          <NavBar />
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
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
