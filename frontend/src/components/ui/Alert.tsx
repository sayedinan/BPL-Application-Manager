import type { ReactNode } from 'react';

export type AlertTone = 'error' | 'success' | 'warning';

const TONE_CLASSES: Record<AlertTone, string> = {
  error: 'border-danger/30 bg-danger-bg text-danger',
  success: 'border-ok/30 bg-ok-bg text-ok',
  warning: 'border-warn/30 bg-warn-bg text-warn',
};

export function Alert({
  tone = 'error',
  children,
  className = '',
  onDismiss,
}: {
  tone?: AlertTone;
  children: ReactNode;
  className?: string;
  /** When set, shows a dismiss button so stale errors don't sit on screen forever. */
  onDismiss?: () => void;
}): JSX.Element {
  return (
    <div
      role="alert"
      className={[
        'flex items-start gap-2 rounded-lg border px-3 py-2.5 text-sm',
        TONE_CLASSES[tone],
        className,
      ].join(' ')}
    >
      <div className="min-w-0 flex-1">{children}</div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="-mr-1 shrink-0 rounded p-0.5 opacity-70 hover:opacity-100"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      )}
    </div>
  );
}
