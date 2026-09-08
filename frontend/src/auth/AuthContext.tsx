// AuthContext — holds the SPA's view of the current user.
//
// Populated by the page-load hydration call to GET /api/v1/auth/me
// (see `app/AuthHydrator.tsx`). The server is the source of truth
// for auth state; this context is just the in-memory cache that
// the rest of the SPA reads from. Per the
// frontend-auth-session-handling skill: "Do NOT store the
// session/auth state only in client-side memory or localStorage
// as the source of truth — always confirm against GET /api/auth/me
// on load." The context is the local cache; the server is the
// authority.

import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export type Role = 'SYS_ADMIN' | 'ADMIN' | 'USER';

export interface CurrentUser {
  id: number;
  username: string;
  role: Role;
  mustChangePassword: boolean;
  assignedApplicationIds: number[];
}

export interface AuthState {
  /**
   * One of three states:
   *  - 'unknown': hydration has not yet completed
   *  - 'authenticated': the user is signed in
   *  - 'unauthenticated': hydration returned 401 (the wrapper
   *    has already redirected to /login; the context's job is
   *    to mark the state for any rendering that happens before
   *    the redirect completes)
   */
  status: 'unknown' | 'authenticated' | 'unauthenticated';
  user: CurrentUser | null;
}

export interface AuthContextValue extends AuthState {
  setUser: (user: CurrentUser | null) => void;
  setStatus: (status: AuthState['status']) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  // The initial state is 'unknown' so feature code can render a
  // loading state until hydration completes. The hydrator runs on
  // mount and calls setUser/setStatus exactly once.
  const [state, setState] = useState<AuthState>({
    status: 'unknown',
    user: null,
  });

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      setUser: (user) => setState((prev) => ({ ...prev, user })),
      setStatus: (status) => setState((prev) => ({ ...prev, status })),
    }),
    [state],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (ctx === null) {
    throw new Error('useAuth must be used inside an <AuthProvider>');
  }
  return ctx;
}
