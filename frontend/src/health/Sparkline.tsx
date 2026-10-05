/**
 * A small live line graph with its label and current value, drawn from the
 * last readings. A missing value (null, e.g. a failed check) leaves a gap in
 * the line instead of being drawn as zero. Returns nothing when no reading
 * has a value, so an application that doesn't report a metric shows no empty
 * graph for it.
 */
export function Sparkline({
  label,
  values,
  unit,
  fixedMax,
  decimals = 0,
}: {
  label: string;
  values: (number | null)[];
  unit: string;
  /** Top of the scale; leave out to scale to the largest value (use 100 for percentages). */
  fixedMax?: number;
  decimals?: number;
}): JSX.Element | null {
  const present = values.filter((v): v is number => v !== null);
  if (present.length === 0) return null;

  const width = 300;
  const height = 40;
  const pad = 3;
  const max = fixedMax ?? Math.max(1, ...present);
  const x = (i: number) => (values.length < 2 ? 0 : (i / (values.length - 1)) * width);
  const y = (v: number) => height - pad - (Math.min(v, max) / max) * (height - 2 * pad);

  let path = '';
  let penDown = false;
  values.forEach((v, i) => {
    if (v === null) {
      penDown = false;
      return;
    }
    path += `${penDown ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)} `;
    penDown = true;
  });

  const current = values[values.length - 1];
  return (
    <div>
      <div className="mb-1 flex justify-between gap-2 text-xs">
        <span className="font-medium text-slate-700 dark:text-gh-fgSoft">{label}</span>
        <span className="text-slate-500 dark:text-gh-muted">
          {current === null ? 'no reading' : `${current.toFixed(decimals)}${unit}`}
        </span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        className="h-10 w-full rounded bg-slate-200/60 dark:bg-gh-hover/40"
        role="img"
        aria-label={`${label} over the last ${values.length} checks`}
      >
        <path
          d={path}
          fill="none"
          className="stroke-brand-500"
          strokeWidth={1.5}
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  );
}
