// 11.6 — wire buttons to Phase 8.12 endpoints; Idempotency-Key header (§4.1)
// Only calls existing endpoints via TanStack Query; reflects response; no new state logic.
export function useStartStop(appId: number, idempotencyKey: string) {
  return {
    start: () => fetch(`/api/v1/applications/${appId}/start`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey, 'Content-Type': 'application/json' }, credentials: 'include' }),
    stop: () => fetch(`/api/v1/applications/${appId}/stop`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey, 'Content-Type': 'application/json' }, credentials: 'include' }),
  };
}
