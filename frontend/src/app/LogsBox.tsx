import { useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Button } from '@/components/ui/Button';
import { isNearBottom } from '@/lib/scroll';
import { Input, Select } from '@/components/ui/Field';
import { useToast } from '@/components/ui/Toast';
import { AuditList, AuditListHeader } from '@/app/AuditList';
import {
  actionLabel,
  EMPTY_AUDIT_FILTERS,
  filterAuditRows,
  filterOptions,
  hasActiveFilters,
  type AuditFilters,
  type AuditLogRow,
} from '@/app/auditFormat';

interface LogLineRow {
  line_number: number;
  content: string;
  captured_at: string;
}

interface ApplicationSummary {
  id: number;
  name: string;
}

type LogSource = 'application' | 'audit';

/** The audit endpoint returns at most this many rows per request. */
const AUDIT_PAGE_SIZE = 500;

export function LogsBox({ role }: { role?: string }) {
  const toast = useToast();
  const canViewAudit = role === 'SYS_ADMIN' || role === 'ADMIN';
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [source, setSource] = useState<LogSource>(canViewAudit ? 'audit' : 'application');
  const [selectedAppId, setSelectedAppId] = useState<number | null>(null);
  // Application source: raw lines. Audit source: structured rows, rendered as a list.
  const [lines, setLines] = useState<string[]>([]);
  const [auditRows, setAuditRows] = useState<AuditLogRow[]>([]);
  const [auditTotal, setAuditTotal] = useState(0);
  const [filters, setFilters] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [historyError, setHistoryError] = useState<string | null>(null);
  // True from the moment a selection is made until its history arrives, so an empty
  // list reads as "loading", not "nothing here".
  const [historyLoading, setHistoryLoading] = useState(true);
  const [autoFollow, setAutoFollow] = useState(true);
  const clientRef = useRef<Client | null>(null);

  // Audit Log is always global — every user, every app, chronologically.
  // The app list below is only used to populate the per-app Application
  // Log options in the dropdown; it never scopes the audit fetch.
  useEffect(() => {
    let cancelled = false;
    async function loadApps() {
      try {
        const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
        if (cancelled) return;
        setApps(list);
        setSelectedAppId((prev) => (prev === null && list.length > 0 ? list[0].id : prev));
      } catch {
        // app list failure only limits the Application Log options —
        // Audit Log (if permitted) still works without it
      }
    }
    void loadApps();
    return () => { cancelled = true; };
  }, []);

  // History is fetched once per selection (never per incoming message).
  useEffect(() => {
    if (source === 'application' && selectedAppId === null) return;
    let cancelled = false;
    setLines([]);
    setAuditRows([]);
    setAuditTotal(0);
    setHistoryError(null);
    setHistoryLoading(true);
    setAutoFollow(true);

    async function loadHistory() {
      try {
        if (source === 'application') {
          const rows = await api.get<LogLineRow[]>(API.APPLICATIONS.LOGS(selectedAppId as number));
          if (!cancelled) setLines(rows.map((r) => r.content));
        } else {
          const res = await api.get<{ items: AuditLogRow[]; total?: number }>(
            `${API.AUDIT_LOGS}?page=0&size=${AUDIT_PAGE_SIZE}`,
          );
          if (!cancelled) {
            // Server returns newest first; the viewer reads oldest to newest and follows the bottom.
            setAuditRows(res.items.slice().reverse());
            setAuditTotal(res.total ?? res.items.length);
          }
        }
      } catch (err) {
        if (!cancelled) {
          setHistoryError(err instanceof ApiError ? err.message : 'Failed to load log history.');
        }
      } finally {
        if (!cancelled) setHistoryLoading(false);
      }
    }

    void loadHistory();
    return () => { cancelled = true; };
  }, [source, selectedAppId]);

  // One topic at a time: the cleanup deactivates the old client before the next
  // effect run subscribes to the new topic.
  useEffect(() => {
    if (source === 'application' && selectedAppId === null) return;
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;
    const client = new Client({ brokerURL, debug: () => {} });
    const topic = source === 'application' ? `/topic/application-logs/${selectedAppId}` : '/topic/audit-log';

    client.onConnect = () => {
      setReconnectAttempt(0);
      client.subscribe(topic, (msg) => {
        if (source === 'application') {
          try {
            setLines((prev) => [...prev, JSON.parse(msg.body).content]);
          } catch {
            setLines((prev) => [...prev, msg.body]);
          }
          return;
        }
        try {
          const row = JSON.parse(msg.body) as AuditLogRow;
          // A row can arrive both in the history fetch and live; keep one copy.
          setAuditRows((prev) => (row.id != null && prev.some((r) => r.id === row.id) ? prev : [...prev, row]));
          setAuditTotal((n) => n + 1);
        } catch {
          // Ignore a malformed audit message rather than showing a broken row.
        }
      });
    };

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, [source, selectedAppId]);

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
  // Say so as soon as the connection is retrying, not only after ten failed attempts.
  const reconnecting = reconnectAttempt >= 1 && !showBanner;
  const scrollRef = useRef<HTMLDivElement>(null);

  const visibleAudit = useMemo(() => filterAuditRows(auditRows, filters), [auditRows, filters]);
  const options = useMemo(() => filterOptions(auditRows), [auditRows]);

  const contentRef = useRef<HTMLDivElement>(null);
  const autoFollowRef = useRef(autoFollow);
  const lastAutoScrollAt = useRef(0);
  useEffect(() => {
    autoFollowRef.current = autoFollow;
  }, [autoFollow]);

  function scrollToBottom() {
    const el = scrollRef.current;
    if (!el) return;
    lastAutoScrollAt.current = Date.now();
    el.scrollTop = el.scrollHeight;
  }

  useEffect(() => {
    if (autoFollow) scrollToBottom();
  }, [lines, visibleAudit, autoFollow]);

  // While following, keep pinned to the bottom when the content grows after the
  // scroll (web fonts swapping in, rows wrapping at a new width, late layout).
  useEffect(() => {
    const content = contentRef.current;
    if (!content || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => {
      if (autoFollowRef.current) scrollToBottom();
    });
    observer.observe(content);
    return () => observer.disconnect();
  }, [source]);

  // Scrolling up to read older lines pauses auto-follow; being at the bottom does not.
  // Scroll events caused by our own scroll-to-bottom are ignored: if layout shifts
  // right after we scroll, the event would otherwise misread that as the user leaving the bottom.
  const handleScroll = () => {
    if (Date.now() - lastAutoScrollAt.current < 250) return;
    if (scrollRef.current && !isNearBottom(scrollRef.current)) {
      setAutoFollow(false);
    }
  };

  const selectValue = source === 'audit' ? 'audit' : selectedAppId !== null ? `app-${selectedAppId}` : '';

  function handleSelectChange(value: string) {
    setFilters(EMPTY_AUDIT_FILTERS);
    if (value === 'audit') {
      setSource('audit');
    } else if (value.startsWith('app-')) {
      setSource('application');
      setSelectedAppId(Number(value.slice(4)));
    }
  }

  async function copyLines() {
    try {
      await navigator.clipboard.writeText(lines.join('\n'));
      toast.push('success', `Copied ${lines.length} log ${lines.length === 1 ? 'line' : 'lines'}.`);
    } catch {
      toast.push('error', "Couldn't copy to the clipboard. Select the text and copy it instead.");
    }
  }

  const setFilter = (patch: Partial<AuditFilters>) => setFilters((f) => ({ ...f, ...patch }));
  const filtering = hasActiveFilters(filters);

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-rule px-4 py-3">
        <Select
          aria-label="Log source"
          value={selectValue}
          onChange={(e) => handleSelectChange(e.target.value)}
          className="!w-auto py-1.5 text-xs font-medium"
        >
          {canViewAudit && <option value="audit">Audit log</option>}
          {apps.map((app) => (
            <option key={app.id} value={`app-${app.id}`}>{app.name} — Application log</option>
          ))}
        </Select>
        <div className="flex items-center gap-3">
          {source === 'application' && (
            <Button size="sm" variant="secondary" onClick={() => void copyLines()} disabled={lines.length === 0}>
              Copy log
            </Button>
          )}
          <span
            role="status"
            className={`flex items-center gap-1.5 text-xs ${showBanner ? 'text-danger' : reconnecting ? 'text-warn' : 'text-muted'}`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${showBanner ? 'bg-danger' : reconnecting ? 'bg-warn' : 'bg-ok animate-pulse-soft'}`}
              aria-hidden="true"
            />
            {showBanner ? 'Disconnected' : reconnecting ? 'Reconnecting…' : 'Live'}
          </span>
        </div>
      </div>

      {source === 'audit' && (
        <div className="border-b border-rule px-4 py-3">
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-[minmax(0,2fr)_repeat(3,minmax(0,1fr))]">
            <Input
              type="search"
              aria-label="Search audit log"
              placeholder="Search"
              value={filters.search}
              onChange={(e) => setFilter({ search: e.target.value })}
            />
            <Select aria-label="Filter by user" value={filters.user} onChange={(e) => setFilter({ user: e.target.value })}>
              <option value="">All users</option>
              {options.users.map((u) => <option key={u} value={u}>{u}</option>)}
            </Select>
            <Select aria-label="Filter by action" value={filters.action} onChange={(e) => setFilter({ action: e.target.value })}>
              <option value="">All actions</option>
              {options.actions.map((a) => <option key={a} value={a}>{actionLabel(a)}</option>)}
            </Select>
            <Select aria-label="Filter by application" value={filters.app} onChange={(e) => setFilter({ app: e.target.value })}>
              <option value="">All applications</option>
              {options.apps.map((a) => <option key={a} value={a}>{a}</option>)}
            </Select>
            {/* Own row with visible labels: native date inputs are wide and their
                dd/mm/yyyy placeholder says nothing about which end of the range it is. */}
            {/* Stacked on phones: two native date inputs side by side truncate their value. */}
            <div className="flex flex-col gap-2 sm:col-span-2 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-4 lg:col-span-4">
              <label className="flex min-w-0 items-center gap-2 text-xs text-muted">
                <span className="w-9 shrink-0 sm:w-auto">From</span>
                <Input type="date" value={filters.from} max={filters.to || undefined} onChange={(e) => setFilter({ from: e.target.value })} className="min-w-0 flex-1 sm:w-44 sm:flex-none" />
              </label>
              <label className="flex min-w-0 items-center gap-2 text-xs text-muted">
                <span className="w-9 shrink-0 sm:w-auto">To</span>
                <Input type="date" value={filters.to} min={filters.from || undefined} onChange={(e) => setFilter({ to: e.target.value })} className="min-w-0 flex-1 sm:w-44 sm:flex-none" />
              </label>
            </div>
          </div>
          <p className="mt-2 flex flex-wrap items-center gap-x-3 text-xs text-muted" role="status">
            <span className="tabular">
              {filtering ? `${visibleAudit.length} of ${auditRows.length} entries match` : `${auditRows.length} entries`}
            </span>
            {auditTotal > auditRows.length && (
              <span className="font-medium text-warn">
                Showing the latest {auditRows.length} of {auditTotal} entries. Search and filters cover these only,
                so older matches won't appear.
              </span>
            )}
            {filtering && (
              <button type="button" onClick={() => setFilters(EMPTY_AUDIT_FILTERS)} className="font-medium text-accent hover:underline">
                Clear filters
              </button>
            )}
          </p>
        </div>
      )}

      {historyError && (
        <p role="alert" className="border-b border-danger/30 bg-danger-bg px-4 py-2 text-sm text-danger">
          {historyError}
        </p>
      )}

      <div className="relative">
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          tabIndex={0}
          aria-label={source === 'audit' ? 'Audit log entries' : 'Application log output'}
          className={`h-[60vh] min-h-[20rem] overflow-y-auto ${source === 'audit' ? 'bg-panel' : 'bg-brand-950'}`}
        >
          {/* Measured by the ResizeObserver above to keep following as content grows. */}
          <div ref={contentRef}>
            {source === 'audit' ? (
              <>
                <AuditListHeader />
                {visibleAudit.length > 0 ? (
                  <AuditList rows={visibleAudit} />
                ) : (
                  <p className="px-4 py-10 text-center text-sm text-muted">
                    {historyLoading
                      ? 'Loading entries…'
                      : filtering
                        ? 'No entries match these filters.'
                        : 'No audit entries yet.'}
                  </p>
                )}
              </>
            ) : (
              <pre className="whitespace-pre-wrap break-all px-4 py-3 font-mono text-xs leading-relaxed text-brand-100">
                {lines.length > 0 ? lines.join('\n') : historyLoading ? 'Loading log…' : 'No log lines yet.'}
              </pre>
            )}
          </div>
        </div>

        {!autoFollow && (
          <button
            onClick={() => { setAutoFollow(true); scrollToBottom(); }}
            className="absolute bottom-3 left-1/2 inline-flex -translate-x-1/2 items-center gap-1.5 rounded-full bg-brand-600 px-3 py-1.5 text-xs font-medium text-white shadow-popover transition-theme hover:bg-brand-700"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 5v14M6 13l6 6 6-6" />
            </svg>
            Jump to latest
          </button>
        )}
      </div>

      {showBanner && (
        <div role="alert" className="flex items-center justify-between gap-3 border-t border-warn/30 bg-warn-bg px-4 py-2 text-xs text-warn">
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
