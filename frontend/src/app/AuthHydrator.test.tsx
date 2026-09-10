// Tests for AuthHydrator — the page-load auth-state hydration.
//
// Verifies the two cases the SPEC §8.1 / frontend-auth-session-handling
// skill requires:
//   1. GET /api/v1/auth/me returning 200 → populates AuthContext with
//      the current user (id, username, role, mustChangePassword,
//      assignedApplicationIds).
//   2. GET /api/v1/auth/me returning 401 → the apiFetch wrapper
//      has already triggered window.location.assign('/login'); the
//      hydrator marks the context as unauthenticated so any
//      intermediate render doesn't briefly show authenticated UI.

import { act, cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthHydrator } from './AuthHydrator';
import { AuthProvider, useAuth } from '@/auth/AuthContext';

// Test helper component that exposes the AuthContext value as
// a JSON-serializable object via a data attribute. This lets us
// assert on the context state without coupling the test to
// internal implementation details.
function ContextProbe(): JSX.Element {
  const auth = useAuth();
  return (
    <div
      data-testid="probe"
      data-status={auth.status}
      data-user={auth.user ? JSON.stringify(auth.user) : ''}
    />
  );
}

const assignMock = vi.fn();
Object.defineProperty(window, 'location', {
  value: { ...window.location, assign: assignMock, pathname: '/somewhere' },
  writable: true,
  configurable: true,
});

describe('AuthHydrator', () => {
  beforeEach(() => {
    assignMock.mockClear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('populates the AuthContext on a 200 response from /auth/me', async () => {
    const mePayload = {
      id: 1,
      username: 'admin',
      role: 'SYS_ADMIN',
      mustChangePassword: true,
      assignedApplicationIds: [3, 4],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(mePayload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    render(
      <AuthProvider>
        <AuthHydrator />
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      const probe = document.querySelector('[data-testid="probe"]') as HTMLElement | null;
      expect(probe?.getAttribute('data-status')).toBe('authenticated');
    });
    const probe = document.querySelector('[data-testid="probe"]') as HTMLElement;
    const userJson = probe.getAttribute('data-user') ?? '';
    expect(JSON.parse(userJson)).toEqual(mePayload);
    // The wrapper should NOT have triggered a redirect.
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('marks the context as unauthenticated when /auth/me returns 401', async () => {
    // The apiFetch wrapper, on 401, calls window.location.assign
    // and returns a never-resolving promise. The mock for fetch
    // therefore never has its .json() awaited for a real
    // response — it just resolves with the 401 status.
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 401 }));
    assignMock.mockImplementation(() => {
      // In a real browser this would change window.location.
      // In jsdom, just throw to verify the wrapper doesn't try
      // to continue.
    });

    render(
      <AuthProvider>
        <AuthHydrator />
        <ContextProbe />
      </AuthProvider>,
    );

    // The redirect was triggered.
    await waitFor(() => {
      expect(assignMock).toHaveBeenCalledWith('/login');
    });
    // The context marks the user as unauthenticated so any
    // intermediate render doesn't show authenticated UI.
    await waitFor(() => {
      const probe = document.querySelector('[data-testid="probe"]') as HTMLElement | null;
      expect(probe?.getAttribute('data-status')).toBe('unauthenticated');
    });
    const probe = document.querySelector('[data-testid="probe"]') as HTMLElement;
    expect(probe.getAttribute('data-user')).toBe('');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('only hydrates once even when the effect runs twice (React 18 StrictMode guard)', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          id: 1,
          username: 'admin',
          role: 'SYS_ADMIN',
          mustChangePassword: false,
          assignedApplicationIds: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    const { rerender } = render(
      <AuthProvider>
        <AuthHydrator />
        <ContextProbe />
      </AuthProvider>,
    );
    await waitFor(() => {
      const probe = document.querySelector('[data-testid="probe"]') as HTMLElement | null;
      expect(probe?.getAttribute('data-status')).toBe('authenticated');
    });
    // Re-render to simulate a React 18 StrictMode double-invoke
    // (the effect would normally be cleaned up and re-run, but
    // the ranRef guard prevents the fetch from being issued a
    // second time).
    await act(async () => {
      rerender(
        <AuthProvider>
          <AuthHydrator />
          <ContextProbe />
        </AuthProvider>,
      );
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
