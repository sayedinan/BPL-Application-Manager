/**
 * The company logo's "C", redrawn from its slanted blocks. This is the one
 * signature detail of the design: sidebar and sign-in panel only. Decorative.
 */
const ROWS = [
  '..#####.',
  '.#######',
  '.##...##',
  '###...##',
  '###.....',
  '###.....',
  '###.....',
  '###.....',
  '###....#',
  '.#######',
  '..#####.',
];

export function BlockMark({
  size = 32,
  className = '',
}: {
  size?: number;
  className?: string;
}): JSX.Element {
  return (
    <svg
      viewBox="0 0 108 110"
      height={size}
      width={(size * 108) / 110}
      className={className}
      fill="currentColor"
      aria-hidden="true"
    >
      <g transform="translate(28 0) skewX(-15)">
        {ROWS.flatMap((row, y) =>
          [...row].map((cell, x) =>
            cell === '#' ? (
              <rect key={`${x}-${y}`} x={x * 10} y={y * 10} width="9" height="9" />
            ) : null,
          ),
        )}
      </g>
    </svg>
  );
}
