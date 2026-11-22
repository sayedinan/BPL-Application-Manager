import type { ReactNode } from 'react';

export type BadgeTone = 'online' | 'offline' | 'pending' | 'error' | 'neutral' | 'maintenance';

const TONE_CLASSES: Record<BadgeTone, string> = {
  online: 'bg-ok-bg text-ok',
  offline: 'bg-idle-bg text-idle',
  pending: 'bg-warn-bg text-warn',
  error: 'bg-danger-bg text-danger',
  neutral: 'bg-accent/10 text-accent',
  maintenance: 'bg-maint-bg text-maint',
};

/**
 * Status is never conveyed by color alone: every tone has its own shape, so the
 * state still reads for color-blind users and in grayscale.
 *   online = filled circle, offline = hollow circle, pending = half circle,
 *   error = triangle, maintenance = diamond, neutral = square.
 */
export function StatusGlyph({ tone, className = '' }: { tone: BadgeTone; className?: string }): JSX.Element {
  const common = { width: 10, height: 10, viewBox: '0 0 10 10', 'aria-hidden': true, className } as const;
  switch (tone) {
    case 'online':
      return (
        <svg {...common}>
          <circle cx="5" cy="5" r="4" fill="currentColor" />
        </svg>
      );
    case 'offline':
      return (
        <svg {...common}>
          <circle cx="5" cy="5" r="3.25" fill="none" stroke="currentColor" strokeWidth="1.5" />
        </svg>
      );
    case 'pending':
      return (
        <svg {...common}>
          <circle cx="5" cy="5" r="3.25" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <path d="M5 1.75a3.25 3.25 0 0 1 0 6.5z" fill="currentColor" />
        </svg>
      );
    case 'error':
      return (
        <svg {...common}>
          <path d="M5 1 9.2 8.6H.8z" fill="currentColor" />
        </svg>
      );
    case 'maintenance':
      return (
        <svg {...common}>
          <path d="M5 .8 9.2 5 5 9.2.8 5z" fill="currentColor" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <rect x="1.5" y="1.5" width="7" height="7" rx="1" fill="currentColor" />
        </svg>
      );
  }
}

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
        'inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium',
        TONE_CLASSES[tone],
      ].join(' ')}
    >
      {dot && <StatusGlyph tone={tone} className={tone === 'pending' ? 'animate-pulse-soft' : ''} />}
      {children}
    </span>
  );
}
