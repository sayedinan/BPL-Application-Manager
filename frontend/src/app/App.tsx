// Root shell.
//
// Provides React Query, the BrowserRouter, and the AuthContext.
// The AuthHydrator runs on mount to call GET /api/v1/auth/me and
// either populate the context (200) or trigger a redirect to
// /login (401). The rest of the route tree — Dashboard,
// Applications, Users, Audit Logs — lands in subsequent slices.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes, Navigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

import { AuthHydrator } from '@/app/AuthHydrator';
import { AuthProvider } from '@/auth/AuthContext';
import { LoginPage } from '@/auth/LoginPage';
import { CreateAdminPage } from '@/app/CreateAdminPage';
import { EnginesPage } from '@/app/EnginesPage';

const queryClient = new QueryClient();

function ForbiddenPage() { return <div style={{padding:40,textAlign:'center'}}><h1>403 — Access Denied</h1><p>You don't have access to this page.</p></div>; }
function DashboardPlaceholder() { return <div>Dashboard (role-gated)</div>; }
function PlaceholderApplications() { return <div>Applications (Sys.Admin)</div>; }
function PlaceholderUsers() { return <div>Users (Admin+)</div>; }
function PlaceholderChangePassword() { return <div>Change Password</div>; }
function SysAdminOnly({children}:{children:React.ReactNode}) { const {user}=useAuth(); return user?.role==="SYS_ADMIN"?<>{children}</>:<ForbiddenPage />; }
function AdminPlus({children}:{children:React.ReactNode}) { const {user}=useAuth(); return (user?.role==="SYS_ADMIN"||user?.role==="ADMIN")?<>{children}</>:<ForbiddenPage />; }

export function App(): JSX.Element {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <AuthHydrator />
          <Routes>
            <Route path="/" element={<DashboardPlaceholder />} />
            {/* MUST_CHANGE_PASSWORD guard: redirect to /change-password when must_change_password=true (§4.3 / §12.2) */}
            <Route path="/applications" element={<SysAdminOnly><PlaceholderApplications /></SysAdminOnly>} />
            <Route path="/users" element={<AdminPlus><PlaceholderUsers /></AdminPlus>} />
            <Route path="/users/create-admin" element={<SysAdminOnly><CreateAdminPage /></SysAdminOnly>} />
            <Route path="/engines" element={<SysAdminOnly><EnginesPage /></SysAdminOnly>} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/change-password" element={<PlaceholderChangePassword />} />
            {/* mustChangePassword guard (§4.3 / §12.2): redirect to /change-password when must_change_password=true */}
            <Route path="/force-change-password" element={<Navigate to="/change-password" />} />
            <Route path="*" element={<Navigate to="/" />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
