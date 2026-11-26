// The signed-in frame: navy sidebar on wide screens, top bar + drawer below.
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Dialog, DialogBackdrop, DialogPanel } from '@headlessui/react';
import { api } from '@/api/client';
import { API } from '@/api/endpoints';
import { useAuth, type CurrentUser } from '@/auth/AuthContext';
import { BlockMark } from '@/components/ui/BlockMark';
import { ThemeToggle } from '@/components/ui/ThemeToggle';
import { UserMenu } from '@/components/ui/UserMenu';

const ICONS = {
  dashboard: 'M3 3h8v8H3zM13 3h8v5h-8zM13 10h8v11h-8zM3 13h8v8H3z',
  logs: 'M4 6h16M4 12h16M4 18h10',
  applications: 'M3 4h18v6H3zM3 14h18v6H3zM7 7h.01M7 17h.01',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
};

interface NavItem {
  to: string;
  label: string;
  icon: keyof typeof ICONS;
}

/** Which links a role sees. Mirrors the route guards in App.tsx. */
function navSections(role: CurrentUser['role']): { title: string; items: NavItem[] }[] {
  const adminPlus = role === 'SYS_ADMIN' || role === 'ADMIN';
  const monitor: NavItem[] = [{ to: '/', label: 'Dashboard', icon: 'dashboard' }];
  if (adminPlus) monitor.push({ to: '/logs', label: 'Logs', icon: 'logs' });

  const manage: NavItem[] = [];
  if (role === 'SYS_ADMIN') manage.push({ to: '/applications', label: 'Applications', icon: 'applications' });
  if (adminPlus) manage.push({ to: '/users', label: 'Users', icon: 'users' });

  return [
    { title: 'Monitor', items: monitor },
    { title: 'Manage', items: manage },
  ].filter((section) => section.items.length > 0);
}

// The navy surfaces need a lighter focus ring than the light-theme accent.
const NAVY = 'navy-surface [--accent:155_179_226]';

function Brand(): JSX.Element {
  return (
    <Link to="/" className="flex items-center gap-3 rounded-lg px-2 py-1 text-white" aria-label="BPL Admin home">
      <BlockMark size={34} className="text-brand-400" />
      <span className="text-base font-bold tracking-tight">BPL Admin</span>
    </Link>
  );
}

function SidebarContent({ user, onLogout }: { user: CurrentUser; onLogout: () => void }): JSX.Element {
  const { pathname } = useLocation();
  return (
    <div className={`flex h-full flex-col px-3 py-5 ${NAVY}`}>
      <Brand />
      <nav aria-label="Main" className="mt-6 min-h-0 flex-1 overflow-y-auto">
        {navSections(user.role).map((section) => (
          <div key={section.title} className="mb-5">
            <p className="mb-1.5 px-3 text-xs font-semibold text-brand-300">{section.title}</p>
            <ul className="space-y-1">
              {section.items.map((item) => {
                // The application details pages (/apps/:name) belong to the Dashboard.
                const active = pathname === item.to || (item.to === '/' && pathname.startsWith('/apps/'));
                return (
                  <li key={item.to}>
                    <Link
                      to={item.to}
                      aria-current={active ? 'page' : undefined}
                      className={[
                        'flex min-h-10 items-center gap-3 rounded-lg px-3 text-sm transition-theme',
                        active
                          ? 'bg-brand-600 font-semibold text-white'
                          : 'font-medium text-brand-100 hover:bg-white/10 hover:text-white',
                      ].join(' ')}
                    >
                      <svg
                        width="18"
                        height="18"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                      >
                        <path d={ICONS[item.icon]} />
                      </svg>
                      {item.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>
      <div className="flex items-center gap-1 border-t border-white/15 pt-3">
        <UserMenu
          username={user.username}
          role={user.role}
          email={user.email}
          phoneNumber={user.phoneNumber}
          onLogout={onLogout}
        />
        <ThemeToggle className="text-brand-100 hover:bg-white/10 hover:text-white" />
      </div>
    </div>
  );
}

export function Shell({ children }: { children: ReactNode }): JSX.Element {
  const { status, user, setUser, setStatus } = useAuth();
  const { pathname } = useLocation();
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Close the drawer after navigating.
  useEffect(() => {
    setDrawerOpen(false);
  }, [pathname]);

  // No navigation on sign-in, or while a password change is forced.
  if (status !== 'authenticated' || !user || pathname === '/login' || user.mustChangePassword) {
    return <main className="min-h-screen">{children}</main>;
  }

  async function handleLogout() {
    try {
      await api.post(API.AUTH.LOGOUT, undefined, { skipAuthRedirect: true });
    } finally {
      setUser(null);
      setStatus('unauthenticated');
      // Deliberate full reload: tears down the session state and WebSocket cleanly.
      window.location.assign('/login');
    }
  }

  return (
    <div className="min-h-screen lg:pl-[220px]">
      <a
        href="#content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-panel focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-ink focus:shadow-popover"
      >
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-30 hidden w-[220px] shadow-[8px_0_28px_-12px_rgb(14_26_51/0.55)] lg:block">
        <SidebarContent user={user} onLogout={handleLogout} />
      </aside>

      <header className={`sticky top-0 z-20 flex items-center justify-between px-3 py-2 lg:hidden ${NAVY}`}>
        <Brand />
        <button
          type="button"
          onClick={() => setDrawerOpen(true)}
          aria-expanded={drawerOpen}
          aria-label="Open menu"
          className="inline-flex h-11 w-11 items-center justify-center rounded-lg text-white hover:bg-white/10"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
        </button>
      </header>

      <Dialog open={drawerOpen} onClose={setDrawerOpen} className="relative z-40 lg:hidden">
        <DialogBackdrop className="fixed inset-0 bg-black/50" />
        <DialogPanel aria-label="Menu" className="fixed inset-y-0 left-0 w-64 max-w-[85vw]">
          <SidebarContent user={user} onLogout={handleLogout} />
        </DialogPanel>
      </Dialog>

      <main id="content" tabIndex={-1} className="min-w-0 scroll-mt-16 focus:outline-none">
        {children}
      </main>
    </div>
  );
}
