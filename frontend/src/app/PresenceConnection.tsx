import { useEffect } from 'react';
import { Client } from '@stomp/stompjs';
import { useAuth } from '@/auth/AuthContext';

// Keeps one lightweight WebSocket open on every page while logged in, so the
// server can tell when this browser goes away (Online/Offline + logout audit).
export function PresenceConnection(): null {
  const { status } = useAuth();

  useEffect(() => {
    if (status !== 'authenticated') return;
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const client = new Client({
      brokerURL: `${wsProtocol}//${window.location.host}/ws`,
      debug: () => {},
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [status]);

  return null;
}
