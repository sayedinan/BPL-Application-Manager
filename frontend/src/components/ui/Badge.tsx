import type { ReactNode } from 'react';

export type BadgeTone = 'online' | 'offline' | 'pending' | 'error' | 'neutral';

const TONE_CLASSES: Record<BadgeTone, string> = {
  online:
    'bg-status-onlineBg text-status-online dark:bg-green-500/10 dark:text-green-400',
  offline:
    'bg-status-offlineBg text-status-offline dark:bg-slate-500/10 dark:text-slate-400',
  pending:
    'bg-status-pendingBg text-status-pending dark:bg-amber-500/10 dark:text-amber-400 animate-pulse-soft',
  error:
    'bg-status-errorBg text-status-error dark:bg-red-500/10 dark:text-red-400',
  neutral:
    'bg-brand-100 text-brand-700 dark:bg-brand-500/10 dark:text-brand-300',
};

const DOT_CLASSES: Record<BadgeTone, string> = {
  online: 'bg-status-online',
  offline: 'bg-status-offline',
  pending: 'bg-status-pending',
  error: 'bg-status-error',
  neutral: 'bg-brand-500',
};

export function Badge({
  tone = 'neutral',
  children,
  dot = true,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  dot?: boolean;
}): JSX.Element {
  return (
    <span
      className={[
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium',
        TONE_CLASSES[tone],
      ].join(' ')}
    >
      {dot && <span className={`h-1.5 w-1.5 rounded-full ${DOT_CLASSES[tone]}`} />}
      {children}
    </span>
  );
}
