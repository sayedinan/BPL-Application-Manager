# `common/`

Cross-feature UI primitives and shared components. Anything used by
more than one feature folder lives here — feature-specific components
stay inside their own folder.

Planned files:

- `components/AccessDeniedPage.tsx` — explicit 403 page (SPEC §9.5,
  not a silent redirect)
- `components/ErrorBoundary.tsx`
- `components/LoadingSpinner.tsx`
- `components/StatusBadge.tsx` (shared if the dashboard needs the
  same treatment elsewhere)
- `hooks/useDebouncedValue.ts` and other small utilities
