import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Spinner } from './Spinner';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';
type Size = 'sm' | 'md';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

const VARIANT_CLASSES: Record<Variant, string> = {
  primary:
    'bg-brand-600 text-white hover:bg-brand-700 focus-visible:ring-brand-500 disabled:bg-brand-600/50',
  secondary:
    'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 focus-visible:ring-brand-500 ' +
    'dark:bg-surface-darkSubtle dark:text-gh-fg dark:border-gh-border dark:hover:bg-gh-hover',
  danger:
    'bg-red-600 text-white hover:bg-red-700 focus-visible:ring-red-500 disabled:bg-red-600/50',
  ghost:
    'bg-transparent text-slate-600 hover:bg-slate-100 focus-visible:ring-brand-500 ' +
    'dark:text-gh-fgSoft dark:hover:bg-gh-hover',
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
        'inline-flex items-center justify-center gap-1.5 rounded-xl font-medium',
        'transition-theme focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
        'dark:focus-visible:ring-offset-surface-dark',
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
