
import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';

interface LogLineRow {
  line_number: number;
  content: string;
  captured_at: string;
}

interface AuditLogRow {
  id: number;
  timestamp: string;
  actorUsername: string;
  actorRole: string;
  actionType: string;
  targetApplicationId: number | null;
  targetApplicationName: string | null;
  targetUserId: number | null;
  result: string;
}

type LogSource = 'application' | 'audit';

function formatAuditLine(row: AuditLogRow): string {
  const target = row.targetApplicationName ?? (row.targetUserId != null ? `user#${row.targetUserId}` : '-');
  return `${row.timestamp} ${row.actorUsername}(${row.actorRole}) ${row.actionType} target=${target} result=${row.result}`;
}

export function LogsBox({ appId, appName, role }: { appId: number; appName: string; role?: string }) {
  const canViewAudit = role === 'SYS_ADMIN' || role === 'ADMIN';
  const [source, setSource] = useState<LogSource>('application');
  const [lines, setLines] = useState<string[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [autoFollow, setAutoFollow] = useState(true);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLines([]);
    setHistoryError(null);
    setAutoFollow(true);

    async function loadHistory() {
      try {
        if (source === 'application') {
          const rows = await api.get<LogLineRow[]>(API.APPLICATIONS.LOGS(appId));
          if (!cancelled) setLines(rows.map((r) => r.content));
        } else {
          const res = await api.get<{ items: AuditLogRow[] }>(`${API.AUDIT_LOGS}?page=0&size=500`);
          if (!cancelled) setLines(res.items.slice().reverse().map(formatAuditLine));
        }
      } catch (err) {
        if (!cancelled) {
          setHistoryError(err instanceof ApiError ? err.message : 'Failed to load log history.');
        }
      }
    }

    void loadHistory();
    return () => { cancelled = true; };
  }, [appId, source]);

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });
    const topic = source === 'application' ? `/topic/application-logs/${appId}` : '/topic/audit-log';

    client.onConnect = () => client.subscribe(topic, (msg) => {
      try {
        const d = JSON.parse(msg.body);
        const line = source === 'application' ? d.content : formatAuditLine(d);
        setLines((prev) => [...prev, line]);
      } catch {
        setLines((prev) => [...prev, msg.body]);
      }
    });

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, [appId, source]);

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
    <div>
      <select value={source} onChange={(e) => setSource(e.target.value as LogSource)}>
        <option value="application">{appName} — Application Log</option>
        {canViewAudit && <option value="audit">Audit Log (Admin+ only)</option>}
      </select>
      {historyError && <p role="alert" className="text-sm text-red-600">{historyError}</p>}
      <div ref={scrollRef} onScroll={handleScroll} style={{ overflowY: 'auto', height: 400 }}>
        <pre>{lines.join('\n')}</pre>
      </div>
      {!autoFollow && (
        <button onClick={() => { setAutoFollow(true); if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }}>
          ↓ Jump to latest
        </button>
      )}
      {showBanner && <div role="alert">WebSocket disconnected — reconnect failed after 10 attempts.</div>}
    </div>
  );
}
