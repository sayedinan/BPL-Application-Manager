import type { ReactNode } from 'react';

export type AlertTone = 'error' | 'success' | 'warning';

const TONE_CLASSES: Record<AlertTone, string> = {
  error:
    'border-status-error/30 bg-status-errorBg text-status-error ' +
    'dark:border-red-500/20 dark:bg-red-500/10 dark:text-red-400',
  success:
    'border-status-online/30 bg-status-onlineBg text-status-online ' +
    'dark:border-green-500/20 dark:bg-green-500/10 dark:text-green-400',
  warning:
    'border-status-pending/30 bg-status-pendingBg text-status-pending ' +
    'dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-400',
};

export function Alert({
  tone = 'error',
  children,
  className = '',
}: {
  tone?: AlertTone;
  children: ReactNode;
  className?: string;
}): JSX.Element {
  return (
    <div
      role="alert"
      className={['rounded-lg border px-3 py-2.5 text-sm', TONE_CLASSES[tone], className].join(' ')}
    >
      {children}
    </div>
  );
}