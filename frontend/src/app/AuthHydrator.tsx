// AuthHydrator — page-load hydration for auth state.
//
// On mount, calls GET /api/v1/auth/me (via the shared `apiFetch`
// client). The client is configured to redirect to /login on
// 401, so:
//   - 200 → set the user in AuthContext
//   - 401 → the client has already redirected; this component
//     just sets the context to 'unauthenticated' so any rendered
//     tree before the redirect commits knows the state
//   - any other error → set 'unauthenticated' and let the SPA
//     render a generic error state
//
// Why an effect and not a top-level await: the App component
// must render the QueryClient/BrowserRouter providers before
// any child hooks can run, so a top-level await would block the
// entire app shell. The hydrator is rendered as a child of the
// providers and runs on first mount.

import { useEffect, useRef } from 'react';
import { apiFetch, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth, type CurrentUser } from '@/auth/AuthContext';

interface MeResponse {
  id: number;
  username: string;
  role: CurrentUser['role'];
  mustChangePassword: boolean;
  assignedApplicationIds: number[];
}

export function AuthHydrator(): null {
  const { setUser, setStatus } = useAuth();
  // Guard against React 18 StrictMode's double-invoke in
  // development. The hydrator must run exactly once per
  // page load, even though the effect runs twice in dev.
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) {
      return;
    }
    ranRef.current = true;

    void (async () => {
      try {
        const me = await apiFetch<MeResponse>(API.AUTH.ME);
        setUser(me);
        setStatus('authenticated');
      } catch (err) {
        // 401 path: the apiFetch wrapper has already redirected
        // to /login via window.location.assign. We just need to
        // mark the context as unauthenticated so any synchronous
        // re-render before the redirect commits doesn't briefly
        // show authenticated content.
        if (err instanceof ApiError && err.status === 401) {
          setUser(null);
          setStatus('unauthenticated');
          return;
        }
        // Any other failure: treat as unauthenticated. A real
        // production SPA would show an error banner here, but
        // per the slice scope ("only 401 handling") we don't.
        setUser(null);
        setStatus('unauthenticated');
      }
    })();
  }, [setUser, setStatus]);

  // This component renders nothing. The redirect (when
  // applicable) is performed by the apiFetch wrapper as a side
  // effect of the failed call.
  return null;
}
