
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
  // row.timestamp is a correct UTC ISO instant (e.g. "...488654Z") —
  // it was just being printed raw instead of converted to the
  // viewer's local time, unlike the "Started" line elsewhere on this
  // page which already uses toLocaleString(). Match that behavior here.
  const localTime = new Date(row.timestamp).toLocaleString();
  return `${localTime} ${row.actorUsername}(${row.actorRole}) ${row.actionType} target=${target} result=${row.result}`;
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

    client.onConnect = () => {
      // A reconnect actually succeeded — clear the failure count so
      // a stale "disconnected" banner doesn't linger forever after
      // the backend comes back up. Previously this was never reset,
      // so once 10 attempts were exhausted the banner stayed stuck
      // permanently even after the socket reconnected, and the only
      // way out was a full page reload.
      setReconnectAttempt(0);
      client.subscribe(topic, (msg) => {
        try {
          const d = JSON.parse(msg.body);
          const line = source === 'application' ? d.content : formatAuditLine(d);
          setLines((prev) => [...prev, line]);
        } catch {
          setLines((prev) => [...prev, msg.body]);
        }
      });
    };

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, [appId, source]);

  // Backend restarts for routine deploys can take well over a
  // couple of minutes (Gradle rebuild + Docker recreate), so cap the
  // backoff at 30s but keep retrying indefinitely instead of giving
  // up after a fixed attempt count — a redeploy shouldn't strand
  // anyone with a stale dashboard tab open.
  const reconnectDelays = [1000, 2000, 4000, 8000, 16000, 30000];
  const [reconnectAttempt, setReconnectAttempt] = useState(0);

  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected) {
      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];
      const timer = setTimeout(() => { setReconnectAttempt((a) => a + 1); clientRef.current?.activate(); }, delay);
      return () => clearTimeout(timer);
    }
  }, [reconnectAttempt]);

  // Still surface a banner after a while so a genuinely stuck
  // connection isn't silently invisible — but it's now advisory, not
  // a dead end: retries keep happening underneath it, and a manual
  // button is offered too in case someone wants to force it sooner
  // (e.g. right after they know a deploy just finished).
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
      {showBanner && (
        <div role="alert">
          WebSocket disconnected — still retrying every 30s.{' '}
          <button onClick={() => { setReconnectAttempt(0); clientRef.current?.activate(); }}>
            Reconnect now
          </button>
        </div>
      )}
    </div>
  );
}
