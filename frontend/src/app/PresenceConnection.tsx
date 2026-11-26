import { useEffect } from 'react';
import { useAuth } from '@/auth/AuthContext';
import { startLiveStream, stopLiveStream } from '@/lib/liveStream';

// Keeps the tab's one live WebSocket open on every page while logged in. The
// server uses it to tell when this browser goes away (Online/Offline + logout
// audit), and pages follow live updates over the same connection (see
// lib/liveStream.ts) instead of opening their own.
export function PresenceConnection(): null {
  const { status, user } = useAuth();
  // The server refuses the connection until a forced password change is done.
  const locked = Boolean(user?.mustChangePassword);

  useEffect(() => {
    if (status !== 'authenticated' || locked) return;
    startLiveStream();
    return () => {
      stopLiveStream();
    };
  }, [status, locked]);

  return null;
}
