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
        'rounded-xl border bg-panel transition-theme',
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
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight text-ink sm:text-2xl">{title}</h1>
        {description && <p className="mt-1 max-w-prose text-sm text-muted">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
