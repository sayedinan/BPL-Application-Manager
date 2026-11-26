export type Filter = 'all' | 'attention' | 'healthy' | 'maintenance';

// Class names are written out in full so Tailwind can find them.
const TILES: { key: Filter; label: string; tone: string; bar: string; wash: string; chip: string; icon: string }[] = [
  {
    key: 'all',
    label: 'Applications',
    tone: 'text-ink',
    bar: 'bg-brand-500',
    wash: 'from-brand-500/10',
    chip: 'bg-accent/10 text-accent',
    icon: 'M3 4h18v6H3zM3 14h18v6H3zM7 7h.01M7 17h.01',
  },
  {
    key: 'healthy',
    label: 'Healthy',
    tone: 'text-ok',
    bar: 'bg-ok',
    wash: 'from-ok/10',
    chip: 'bg-ok-bg text-ok',
    icon: 'M5 12.5l4.5 4.5L19 7.5',
  },
  {
    key: 'attention',
    label: 'Need attention',
    tone: 'text-danger',
    bar: 'bg-danger',
    wash: 'from-danger/10',
    chip: 'bg-danger-bg text-danger',
    icon: 'M12 4l9 16H3zM12 10v4M12 17h.01',
  },
  {
    key: 'maintenance',
    label: 'Maintenance',
    tone: 'text-maint',
    bar: 'bg-maint',
    wash: 'from-maint/10',
    chip: 'bg-maint-bg text-maint',
    icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.2L4 16.8 7.2 20l5.3-5.3a4 4 0 0 0 5.2-5.4l-2.6 2.6-2.7-.5-.5-2.7z',
  },
];

/** The headline numbers. Each tile is also the filter for the list below it. */
export function SummaryTiles({
  counts,
  active,
  onSelect,
}: {
  counts: Record<Filter, number>;
  active: Filter;
  onSelect: (key: Filter) => void;
}): JSX.Element {
  return (
    <div role="group" aria-label="Filter applications" className="mb-4 grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
      {TILES.map((tile, index) => {
        const selected = tile.key === active;
        const count = counts[tile.key];
        return (
          <button
            key={tile.key}
            type="button"
            aria-pressed={selected}
            onClick={() => onSelect(selected ? 'all' : tile.key)}
            style={{ animationDelay: `${60 + index * 40}ms` }}
            className={[
              'relative animate-rise overflow-hidden rounded-xl border bg-panel px-4 py-3 text-left shadow-card transition duration-200 hover:-translate-y-0.5 hover:shadow-card-hover dark:shadow-card-dark sm:px-5 sm:py-4',
              selected ? 'border-accent ring-1 ring-accent' : 'border-rule hover:border-field',
            ].join(' ')}
          >
            <span aria-hidden="true" className={`absolute inset-x-0 top-0 h-1 ${tile.bar}`} />
            <span
              aria-hidden="true"
              className={`pointer-events-none absolute inset-0 bg-gradient-to-bl to-transparent to-60% ${tile.wash}`}
            />
            <span
              aria-hidden="true"
              className={`absolute right-3 top-1/2 hidden h-11 w-11 -translate-y-1/2 items-center justify-center rounded-xl min-[420px]:flex sm:right-4 ${tile.chip}`}
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d={tile.icon} />
              </svg>
            </span>
            <span className="relative block text-sm font-medium text-muted">{tile.label}</span>
            {/* A zero is not news: it stays neutral whatever the tile is about. */}
            <span
              className={`tabular relative mt-1 block text-3xl font-bold leading-none tracking-tight ${count > 0 ? tile.tone : 'text-ink'}`}
            >
              {count}
            </span>
          </button>
        );
      })}
    </div>
  );
}
