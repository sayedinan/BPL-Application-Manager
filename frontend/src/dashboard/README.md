# `dashboard/`

The `/` route (SPEC §9.2 — visible to all roles). Composes the
application grid, the Logs box, and the role-specific empty states
(SPEC §9.4).

Planned files:

- `DashboardPage.tsx` — top-level composition
- `ApplicationGrid.tsx` — cards (name, status badge, start/stop,
  time-started, running-time) per SPEC §9.3
- `StatusBadge.tsx` — distinct visual treatments for `STARTING` /
  `STOPPING` vs static `RUNNING` / `STOPPED` (SPEC §9.3)
- `EmptyState.tsx` — role-aware empty state (no CTA for User, CTA
  for Sys.Admin)
- `LogViewer/` — the embedded Logs box
  - `LogViewer.tsx` — dropdown + auto-following list
  - `LogDropdown.tsx` — per-role scope (assigned only for User;
    all applications + Audit Log for Admin+)
  - `useLogSubscription.ts` — REST history fetch + WebSocket
    subscription, with clean unsubscribe on dropdown switch
    (SPEC §5.3, §9.3)
