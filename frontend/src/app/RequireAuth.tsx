import { Navigate, useLocation } from 'react-router-dom';

import { useAuth } from '@/auth/AuthContext';
import { LoadingBlock } from '@/components/ui/Spinner';

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status, user } = useAuth();
  const { pathname } = useLocation();
  if (status === 'unknown') {
    return (
      <LoadingBlock className="min-h-[70vh]" />
    );
  }
  if (status !== 'authenticated') return <Navigate to="/login" replace />;
  // SPEC §8.1: the server refuses everything else until the password is changed.
  if (user?.mustChangePassword && pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}
