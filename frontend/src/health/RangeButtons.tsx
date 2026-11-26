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
      className="inline-flex overflow-hidden rounded-lg border border-rule"
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
                ? 'bg-brand-600 text-white'
                : 'bg-panel text-muted hover:bg-sunken')
            }
          >
            {o.label}
          </button>
        );
      })}
    </span>
  );
}
