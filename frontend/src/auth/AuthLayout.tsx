import type { ReactNode } from 'react';
import { BlockMark } from '@/components/ui/BlockMark';
import { Logo } from '@/components/ui/Logo';
import { ThemeToggle } from '@/components/ui/ThemeToggle';

/** Split screen for signed-out and forced-password screens. */
export function AuthLayout({ children }: { children: ReactNode }): JSX.Element {
  return (
    <div className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      <div className="navy-surface hidden flex-col justify-center gap-10 px-16 py-12 lg:flex">
        <BlockMark size={190} className="text-brand-400" />
        <div>
          <p className="text-4xl font-bold leading-tight tracking-tight text-white">
            Every application,
            <br />
            one control room.
          </p>
          <p className="mt-3 text-brand-200">Start, stop and monitor your services from one place.</p>
        </div>
      </div>

      <div className="relative flex flex-col items-center justify-center px-4 py-16">
        <div className="absolute right-4 top-4">
          <ThemeToggle />
        </div>
        <Logo className="mb-6 h-28 w-28 lg:hidden" />
        <div className="w-full max-w-sm animate-fade-in rounded-2xl border border-rule bg-panel p-6 shadow-hero dark:shadow-card-dark sm:p-8">
          {children}
        </div>
      </div>
    </div>
  );
}
