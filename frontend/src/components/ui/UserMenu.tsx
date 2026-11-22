import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

interface UserMenuProps {
  username: string;
  role: 'SYS_ADMIN' | 'ADMIN' | 'USER';
  onLogout: () => void;
  email?: string | null;
  phoneNumber?: string | null;
}

const ROLE_LABEL: Record<UserMenuProps['role'], string> = {
  SYS_ADMIN: 'Sys.Admin',
  ADMIN: 'Admin',
  USER: 'User',
};

export function UserMenu({ username, role, onLogout, email, phoneNumber }: UserMenuProps): JSX.Element {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const initials = username.slice(0, 2).toUpperCase();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex items-center gap-2 rounded-full border border-rule bg-panel py-1 pl-1 pr-3 transition-theme hover:bg-sunken"
      >
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-900 text-xs font-semibold text-white">
          {initials}
        </span>
        <span className="hidden text-left sm:block">
          <span className="block text-sm font-medium leading-tight text-ink">{username}</span>
          <span className="block text-xs leading-tight text-muted">{ROLE_LABEL[role]}</span>
        </span>
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
          className={`text-muted transition-transform ${open ? 'rotate-180' : ''}`}
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-30 mt-2 w-72 overflow-hidden rounded-xl border border-rule bg-panel shadow-popover"
        >
          <div className="border-b border-rule bg-sunken p-4">
            <div className="flex items-center gap-3">
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-brand-900 text-sm font-semibold text-white">
                {initials}
              </span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-ink">{username}</p>
                {email && <p className="truncate text-xs text-muted">{email}</p>}
                {phoneNumber && <p className="truncate font-mono text-xs text-muted">{phoneNumber}</p>}
                <p className="mt-0.5 text-xs font-medium text-muted">{ROLE_LABEL[role]}</p>
              </div>
            </div>
          </div>

          <Link
            to="/change-password"
            role="menuitem"
            onClick={() => setOpen(false)}
            className="flex items-center gap-3 px-4 py-3 transition-theme hover:bg-sunken"
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-sunken text-accent">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <rect x="4" y="11" width="16" height="10" rx="2" />
                <path d="M8 11V7a4 4 0 0 1 8 0v4" />
              </svg>
            </span>
            <span className="flex-1">
              <span className="block text-sm font-medium text-ink">Change password</span>
              <span className="block text-xs text-muted">Update your account password</span>
            </span>
          </Link>

          <div className="border-t border-rule px-4 py-3">
            <button
              type="button"
              role="menuitem"
              onClick={onLogout}
              className="flex items-center gap-2 text-sm font-medium text-danger hover:underline"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
              </svg>
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
