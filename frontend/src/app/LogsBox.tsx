
import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';

interface LogLineRow {
  line_number: number;
  content: string;
  captured_at: string;
}

export function LogsBox({ appId, appName, role }: { appId: number; appName: string; role?: string }) {
  const [lines, setLines] = useState<string[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [autoFollow, setAutoFollow] = useState(true);
  const clientRef = useRef<Client | null>(null);

  // Backfill history first, then attach the live subscription so
  // lines aren't lost between "component mounted" and "WS connected".
  useEffect(() => {
    let cancelled = false;
    setLines([]);
    setHistoryError(null);

    async function loadHistory() {
      try {
        const rows = await api.get<LogLineRow[]>(API.APPLICATIONS.LOGS(appId));
        if (!cancelled) {
          setLines(rows.map((r) => r.content));
        }
      } catch (err) {
        if (!cancelled) {
          setHistoryError(err instanceof ApiError ? err.message : 'Failed to load log history.');
        }
      }
    }

    void loadHistory();
    return () => { cancelled = true; };
  }, [appId]);

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });

    client.onConnect = () => client.subscribe(`/topic/application-logs/${appId}`, (msg) => {
      try {
        const d = JSON.parse(msg.body);
        setLines((prev) => [...prev, d.content]);
      } catch {
        setLines((prev) => [...prev, msg.body]);
      }
    });

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, [appId]);

  const reconnectDelays = [1000, 2000, 4000, 8000, 16000, 30000];
  const [reconnectAttempt, setReconnectAttempt] = useState(0);

  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected) {
      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];
      const timer = setTimeout(() => { setReconnectAttempt((a) => a + 1); clientRef.current?.activate(); }, delay);
      return () => clearTimeout(timer);
    }
  }, [reconnectAttempt]);

  const showBanner = reconnectAttempt >= 10;
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (autoFollow && scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [lines, autoFollow]);

  const handleScroll = () => {
    if (scrollRef.current && scrollRef.current.scrollTop < scrollRef.current.scrollHeight - 50) {
      setAutoFollow(false);
    }
  };

  return (
    <div ref={scrollRef} onScroll={handleScroll} style={{ overflowY: 'auto', height: 400 }}>
      <select>
        <option>{appName} — Application Log</option>
        {(role === 'SYS_ADMIN' || role === 'ADMIN') && <option>Audit Log (Admin+ only)</option>}
      </select>
      {historyError && <p role="alert" className="text-sm text-red-600">{historyError}</p>}
      <pre>{lines.join('\n')}</pre>
      {!autoFollow && (
        <button onClick={() => { setAutoFollow(true); if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }}>
          ↓ Jump to latest
        </button>
      )}
      {showBanner && <div role="alert">WebSocket disconnected — reconnect failed after 10 attempts.</div>}
    </div>
  );
}
