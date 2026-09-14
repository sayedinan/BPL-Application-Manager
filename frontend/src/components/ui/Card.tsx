import type { HTMLAttributes, ReactNode } from 'react';

export function Card({
  className = '',
  interactive = false,
  selected = false,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { interactive?: boolean; selected?: boolean }): JSX.Element {
  return (
    <div
      className={[
        'rounded-xl border bg-white shadow-card transition-theme',
        'dark:bg-surface-darkSubtle dark:border-slate-800',
        selected
          ? 'border-brand-500 ring-1 ring-brand-500'
          : 'border-slate-200 dark:border-slate-800',
        interactive ? 'cursor-pointer hover:shadow-card-hover hover:-translate-y-0.5' : '',
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
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white">{title}</h1>
        {description && (
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{description}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
