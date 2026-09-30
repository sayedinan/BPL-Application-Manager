export function Spinner({ className = 'h-4 w-4' }: { className?: string }): JSX.Element {
  return (
    <svg className={`animate-spin ${className}`} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
    </svg>
  );
}

export function LoadingBlock({
  label = 'Loading…',
  className = '',
}: {
  label?: string;
  className?: string;
}): JSX.Element {
  return (
    <div
      role="status"
      className={`flex items-center justify-center gap-2 text-sm text-slate-500 dark:text-gh-muted ${className}`}
    >
      <Spinner className="h-5 w-5 text-brand-600 dark:text-brand-500" />
      <span>{label}</span>
    </div>
  );
}
