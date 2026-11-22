import type { ReactNode } from 'react';

/**
 * An empty screen is an invitation to act: say what is missing, then give the
 * next step (a button, or who to ask). Callers supply the copy.
 */
export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  icon?: ReactNode;
}): JSX.Element {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-rule bg-panel px-6 py-12 text-center">
      <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-sunken text-muted" aria-hidden="true">
        {icon ?? (
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="4" width="18" height="6" rx="1.5" />
            <rect x="3" y="14" width="18" height="6" rx="1.5" />
            <path d="M7 7h.01M7 17h.01" />
          </svg>
        )}
      </div>
      <h2 className="text-base font-semibold text-ink">{title}</h2>
      {description && <p className="mt-1 max-w-sm text-sm text-muted">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
