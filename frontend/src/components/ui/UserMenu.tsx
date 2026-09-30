import { useEffect, useRef, useState } from 'react';

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
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex items-center gap-2 rounded-full border border-slate-200 bg-white py-1 pl-1 pr-3 transition-theme hover:bg-slate-50 dark:border-gh-border dark:bg-gh-subtle dark:hover:bg-gh-hover"
      >
        <span className="relative flex h-8 w-8 items-center justify-center rounded-full bg-brand-900 text-xs font-bold text-white">
          {initials}
          <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-status-online dark:border-gh-subtle" />
        </span>
        <span className="hidden text-left sm:block">
          <span className="block text-sm font-semibold leading-tight text-slate-900 dark:text-white">{username}</span>
          <span className="block text-xs leading-tight text-slate-500 dark:text-gh-muted">{ROLE_LABEL[role]}</span>
        </span>
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          className={`text-slate-500 transition-transform dark:text-gh-muted ${open ? 'rotate-180' : ''}`}
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-30 mt-2 w-72 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl dark:border-gh-border dark:bg-gh-subtle"
        >
          <div className="bg-gradient-to-br from-brand-900 to-brand-600 p-4 text-white">
            <div className="flex items-center gap-3">
              <span className="relative flex h-12 w-12 items-center justify-center rounded-full bg-white/15 text-base font-bold ring-2 ring-white/40">
                {initials}
                <span className="absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-brand-800 bg-status-online" />
              </span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{username}</p>
                {email && <p className="truncate text-xs text-white/80">{email}</p>}
                {phoneNumber && <p className="truncate font-mono text-xs text-white/70">{phoneNumber}</p>}
                <span className="mt-1 inline-block rounded-full bg-white/15 px-2 py-0.5 text-xs font-medium">
                  {ROLE_LABEL[role]}
                </span>
              </div>
            </div>
          </div>

          <a
            href="/change-password"
            role="menuitem"
            className="flex items-center gap-3 px-4 py-3 transition-theme hover:bg-slate-50 dark:hover:bg-gh-hover"
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-900/40 dark:text-brand-300">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="4" y="11" width="16" height="10" rx="2" />
                <path d="M8 11V7a4 4 0 0 1 8 0v4" />
              </svg>
            </span>
            <span className="flex-1">
              <span className="block text-sm font-semibold text-slate-900 dark:text-white">Change password</span>
              <span className="block text-xs text-slate-500 dark:text-gh-muted">Update your account password</span>
            </span>
          </a>

          <div className="border-t border-slate-200 px-4 py-3 dark:border-gh-border">
            <button
              role="menuitem"
              onClick={onLogout}
              className="flex items-center gap-2 text-sm font-semibold text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
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
