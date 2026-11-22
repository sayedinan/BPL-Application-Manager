import type { HTMLAttributes, TdHTMLAttributes, ThHTMLAttributes } from 'react';

/** Scrolls horizontally inside its own box instead of pushing the page sideways. */
export function Table({ className = '', ...rest }: HTMLAttributes<HTMLTableElement>): JSX.Element {
  return (
    <div className="overflow-x-auto rounded-xl border border-rule bg-panel">
      <table className={['w-full border-collapse text-left text-sm', className].join(' ')} {...rest} />
    </div>
  );
}

export function THead({ className = '', ...rest }: HTMLAttributes<HTMLTableSectionElement>): JSX.Element {
  return <thead className={['bg-sunken text-xs font-medium text-muted', className].join(' ')} {...rest} />;
}

export function Tr({ className = '', ...rest }: HTMLAttributes<HTMLTableRowElement>): JSX.Element {
  return (
    <tr
      className={['border-t border-rule first:border-t-0 transition-theme hover:bg-sunken/60', className].join(' ')}
      {...rest}
    />
  );
}

export function Th({ className = '', ...rest }: ThHTMLAttributes<HTMLTableCellElement>): JSX.Element {
  return <th scope="col" className={['px-4 py-2.5 font-medium', className].join(' ')} {...rest} />;
}

export function Td({ className = '', ...rest }: TdHTMLAttributes<HTMLTableCellElement>): JSX.Element {
  return <td className={['px-4 py-3 align-top text-ink', className].join(' ')} {...rest} />;
}
