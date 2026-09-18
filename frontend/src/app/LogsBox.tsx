import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Button } from '@/components/ui/Button';

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
  const localTime = new Date(row.timestamp).toLocaleString();
  return `${localTime} ${row.actorUsername}(${row.actorRole}) ${row.actionType} target=${target} result=${row.result}`;
}

export function LogsBox({ appId, appName, role }: { appId: number; appName: string; role?: string }) {
  const canViewAudit = role === 'SYS_ADMIN' || role === 'ADMIN';
  const [source, setSource] = useState<LogSource>(canViewAudit ? 'audit' : 'application');
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
          const res = await api.get<{ items: AuditLogRow[] }>(`${API.AUDIT_LOGS}?page=0&size=500&applicationId=${appId}`);
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

  const reconnectDelays = [1000, 2000, 4000, 8000, 16000, 30000];
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });
    const topic = source === 'application' ? `/topic/application-logs/${appId}` : '/topic/audit-log';

    client.onConnect = () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
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
    return () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      client.deactivate();
    };
  }, [appId, source]);

  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected) {
      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];
      reconnectTimerRef.current = setTimeout(() => {
        reconnectTimerRef.current = null;
        setReconnectAttempt((a) => a + 1);
        clientRef.current?.activate();
      }, delay);
      return () => {
        if (reconnectTimerRef.current) {
          clearTimeout(reconnectTimerRef.current);
          reconnectTimerRef.current = null;
        }
      };
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
      <div className="flex items-center justify-between gap-2 border-b border-slate-200 bg-slate-50 px-4 py-2.5 dark:border-slate-800 dark:bg-slate-900/60">
        <select
          value={source}
          onChange={(e) => setSource(e.target.value as LogSource)}
          className="rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-700 transition-theme focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 dark:border-slate-700 dark:bg-surface-dark dark:text-slate-200"
        >
          {canViewAudit && <option value="audit">Audit Log — {appName}</option>}
          <option value="application">{appName} — Application Log</option>
        </select>
        <span className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500">
          <span className={`h-1.5 w-1.5 rounded-full ${showBanner ? 'bg-red-500' : 'bg-status-online animate-pulse-soft'}`} />
          {showBanner ? 'Disconnected' : 'Live'}
        </span>
      </div>

      {historyError && (
        <p role="alert" className="border-b border-red-100 bg-red-50 px-4 py-2 text-sm text-red-700 dark:border-red-500/20 dark:bg-red-500/10 dark:text-red-400">
          {historyError}
        </p>
      )}

      <div className="relative">
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="h-96 overflow-y-auto bg-slate-950 px-4 py-3 font-mono text-xs leading-relaxed text-slate-300"
        >
          <pre className="whitespace-pre-wrap break-all">{lines.length > 0 ? lines.join('\n') : 'No log lines yet…'}</pre>
        </div>

        {!autoFollow && (
          <button
            onClick={() => { setAutoFollow(true); if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-brand-600 px-3 py-1.5 text-xs font-medium text-white shadow-popover transition-theme hover:bg-brand-700"
          >
            ↓ Jump to latest
          </button>
        )}
      </div>

      {showBanner && (
        <div role="alert" className="flex items-center justify-between gap-3 border-t border-status-pending/20 bg-status-pendingBg px-4 py-2 text-xs text-status-pending dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-400">
          <span>WebSocket disconnected — still retrying every 30s.</span>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => { setReconnectAttempt(0); clientRef.current?.activate(); }}
          >
            Reconnect now
          </Button>
        </div>
      )}
    </div>
  );
}
