/**
 * Placeholder shape shown while data loads. The shimmer is a looping animation,
 * which index.css neutralises under prefers-reduced-motion.
 */
export function Skeleton({ className = '' }: { className?: string }): JSX.Element {
  return (
    <div
      aria-hidden="true"
      className={[
        'animate-shimmer rounded-md bg-gradient-to-r from-sunken via-rule/60 to-sunken',
        'bg-[length:200%_100%]',
        className,
      ].join(' ')}
    />
  );
}
