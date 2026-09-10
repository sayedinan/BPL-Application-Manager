// Tests for the shared API client. The two contracts that matter
// for the 401-redirect slice:
//
//   1. Any 401 response triggers window.location.assign('/login').
//   2. Other non-2xx responses throw an ApiError with the SPEC's
//      RFC 7807 `code` field exposed for feature code to branch on.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiFetch, ApiError } from './client';

// Stub for window.location.assign. jsdom provides a real
// implementation but its default behavior is to throw on
// navigation; we want to assert the call without actually
// navigating away from the test runner.
const assignMock = vi.fn();
Object.defineProperty(window, 'location', {
  value: { ...window.location, assign: assignMock, pathname: '/something' },
  writable: true,
  configurable: true,
});

describe('apiFetch — 401 handling', () => {
  beforeEach(() => {
    assignMock.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('redirects to /login on a 401 from a protected endpoint', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 401 }));
    assignMock.mockImplementation(() => {
      // In a real browser this would change window.location. In
      // jsdom, just record the call (no-op).
    });

    let caught: unknown;
    try {
      await apiFetch('/some/protected');
    } catch (e) {
      caught = e;
    }

    expect(fetchMock).toHaveBeenCalledTimes(1);
    // The cookie was sent — credentials: 'include' is the
    // session-cookie transport.
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined;
    expect(init?.credentials).toBe('include');
    // The redirect target is /login.
    expect(assignMock).toHaveBeenCalledWith('/login');
    // The caller gets an ApiError so its catch block can run
    // (e.g. update AuthContext to 'unauthenticated' before the
    // page reloads).
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).status).toBe(401);
    expect((caught as ApiError).code).toBe('INVALID_CREDENTIALS');
  });

  it('does NOT redirect when the caller passes skipAuthRedirect (the login endpoint)', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          type: 'https://errors.bpl-orderapp.com/INVALID_CREDENTIALS',
          title: 'Invalid Credentials',
          status: 401,
          detail: 'Invalid credentials',
          instance: '/api/v1/auth/login',
          code: 'INVALID_CREDENTIALS',
        }),
        { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );

    let caught: unknown;
    try {
      await apiFetch('/auth/login', { method: 'POST', body: {}, skipAuthRedirect: true });
    } catch (e) {
      caught = e;
    }

    // The redirect was NOT called.
    expect(assignMock).not.toHaveBeenCalled();
    // Instead, an ApiError with the SPEC §4.2 code was thrown.
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).status).toBe(401);
    expect((caught as ApiError).code).toBe('INVALID_CREDENTIALS');
  });
});

describe('apiFetch — non-401 error handling', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('surfaces the RFC 7807 code from a 400 response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          type: 'https://errors.bpl-orderapp.com/VALIDATION_FAILED',
          title: 'Validation Failed',
          status: 400,
          detail: 'Request body validation failed',
          instance: '/api/v1/auth/change-password',
          code: 'VALIDATION_FAILED',
          details: { newPassword: 'must be at least 12 characters' },
        }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );

    let caught: unknown;
    try {
      await apiFetch('/auth/change-password', { method: 'POST', body: {} });
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(ApiError);
    const err = caught as ApiError;
    expect(err.status).toBe(400);
    expect(err.code).toBe('VALIDATION_FAILED');
    expect(err.details).toEqual({ newPassword: 'must be at least 12 characters' });
  });

  it('returns the parsed body on a 200 response', async () => {
    const payload = {
      id: 1,
      username: 'admin',
      role: 'SYS_ADMIN',
      mustChangePassword: true,
      assignedApplicationIds: [3, 4],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(payload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const result = await apiFetch<typeof payload>('/auth/me');
    expect(result).toEqual(payload);
  });

  it('returns undefined on a 204 response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }));

    const result = await apiFetch('/auth/logout', { method: 'POST' });
    expect(result).toBeUndefined();
  });
});
