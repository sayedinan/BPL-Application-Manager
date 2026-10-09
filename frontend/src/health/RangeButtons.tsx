/** A row of joined buttons for picking a time range (the selected one is filled). */
export function RangeButtons<K extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly { key: K; label: string }[];
  value: K;
  onChange: (key: K) => void;
}): JSX.Element {
  return (
    <span
      role="group"
      aria-label="Time range"
      className="inline-flex overflow-hidden rounded-lg border border-slate-200 dark:border-gh-border"
    >
      {options.map((o) => {
        const active = o.key === value;
        return (
          <button
            key={o.key}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(o.key)}
            className={
              'px-2.5 py-1 text-xs font-medium transition-colors ' +
              (active
                ? 'bg-brand-500 text-white'
                : 'bg-white text-slate-600 hover:bg-slate-100 dark:bg-surface-dark dark:text-gh-muted dark:hover:bg-gh-hover')
            }
          >
            {o.label}
          </button>
        );
      })}
    </span>
  );
}
