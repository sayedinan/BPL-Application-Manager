import {
  cloneElement,
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type SelectHTMLAttributes,
} from 'react';

const CONTROL_CLASSES = [
  'block w-full rounded-lg border bg-panel px-3 py-2 text-sm text-ink',
  // Full-strength muted: the 70% version measured 4.3:1, under the 4.5:1 minimum.
  'placeholder:text-muted transition-theme',
  'focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/40',
  'disabled:cursor-not-allowed disabled:bg-sunken disabled:text-muted',
].join(' ');

const controlClass = (invalid: boolean, extra = '') =>
  [CONTROL_CLASSES, invalid ? 'border-danger' : 'border-field', extra].join(' ');

/** The shared control look, for forms that lay out their own labels. */
export const CONTROL = controlClass(false);

interface FieldProps {
  label: string;
  /** Helper text under the control. Always visible, linked via aria-describedby. */
  hint?: ReactNode;
  /** Inline error. Marks the control aria-invalid and is announced to screen readers. */
  error?: string | null;
  children: ReactElement;
}

/**
 * Label + control + hint + error, wired together for assistive tech.
 * The child must be a single form control (Input, Select, a textarea...).
 */
export function Field({ label, hint, error, children }: FieldProps): JSX.Element {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium text-ink">
        {label}
      </label>
      {cloneElement(children, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
        invalid: Boolean(error),
      } as Record<string, unknown>)}
      {hint && (
        <p id={hintId} className="mt-1 text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="mt-1 text-xs font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

export const Input = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }
>(function Input({ invalid = false, className = '', ...rest }, ref) {
  return <input ref={ref} className={controlClass(invalid, className)} {...rest} />;
});

export const Select = forwardRef<
  HTMLSelectElement,
  SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }
>(function Select({ invalid = false, className = '', ...rest }, ref) {
  return <select ref={ref} className={controlClass(invalid, className)} {...rest} />;
});
