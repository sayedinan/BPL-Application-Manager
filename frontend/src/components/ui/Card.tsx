import type { HTMLAttributes, ReactNode } from 'react';
import { BlockMark } from './BlockMark';

export function Card({
  className = '',
  interactive = false,
  selected = false,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { interactive?: boolean; selected?: boolean }): JSX.Element {
  return (
    <div
      className={[
        'rounded-xl border bg-panel shadow-card transition-theme dark:shadow-card-dark',
        selected ? 'border-accent ring-1 ring-accent' : 'border-rule',
        interactive ? 'cursor-pointer hover:border-muted/60 hover:shadow-card-hover' : '',
        className,
      ].join(' ')}
      {...rest}
    />
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}): JSX.Element {
  return (
    <div className="hero-surface animate-rise relative mb-5 overflow-hidden rounded-2xl border border-white/10 px-5 py-5 shadow-hero [--accent:155_179_226] sm:px-7 sm:py-6">
      {/* Whole mark, centred; left out where buttons need the space. */}
      {!actions && (
        <BlockMark
          size={80}
          className="pointer-events-none absolute right-6 top-1/2 hidden -translate-y-1/2 text-brand-200 opacity-20 sm:block"
        />
      )}
      <div className="relative flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="break-words text-2xl font-bold tracking-tight text-white sm:text-[1.75rem]">{title}</h1>
          {description && <p className="mt-1 max-w-prose text-sm text-brand-100">{description}</p>}
        </div>
        {/* The ring keeps a blue primary button distinct from the navy behind it. */}
        {actions && <div className="flex flex-wrap items-center gap-2 [&>button]:ring-1 [&>button]:ring-white/30">{actions}</div>}
      </div>
    </div>
  );
}
