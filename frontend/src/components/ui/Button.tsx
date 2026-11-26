import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Spinner } from './Spinner';

type Variant = 'primary' | 'secondary' | 'danger' | 'dangerOutline' | 'ghost';
type Size = 'sm' | 'md';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

const VARIANT_CLASSES: Record<Variant, string> = {
  primary: 'btn-primary bg-brand-600 text-white hover:brightness-110 disabled:shadow-none',
  secondary: 'border border-field/60 bg-panel text-ink hover:bg-sunken',
  // Fixed colors (not the theme token): white text must keep >= 4.5:1 in both themes.
  danger: 'bg-red-700 text-white hover:bg-red-800 dark:bg-red-600 dark:hover:bg-red-700 disabled:opacity-50',
  // For routine destructive actions (Stop): keeps solid red for confirmations only.
  dangerOutline: 'border border-danger/50 bg-panel text-danger hover:bg-danger-bg',
  ghost: 'bg-transparent text-muted hover:bg-sunken hover:text-ink',
};

const SIZE_CLASSES: Record<Size, string> = {
  sm: 'px-2.5 py-1.5 text-xs',
  md: 'px-4 py-2 text-sm',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', className = '', disabled, loading = false, children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      aria-busy={loading}
      className={[
        'inline-flex min-h-6 items-center justify-center gap-1.5 rounded-lg font-semibold',
        'transition-theme focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
        'focus-visible:ring-offset-2 focus-visible:ring-offset-canvas',
        'disabled:cursor-not-allowed disabled:opacity-50',
        VARIANT_CLASSES[variant],
        SIZE_CLASSES[size],
        className,
      ].join(' ')}
      {...rest}
    >
      {loading && <Spinner className={size === 'sm' ? 'h-3 w-3' : 'h-4 w-4'} />}
      {children}
    </button>
  );
});
