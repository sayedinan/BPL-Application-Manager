import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { LoginPage } from './LoginPage';
import { ThemeProvider } from '@/lib/theme';

vi.mock('@/auth/AuthContext', () => ({
  useAuth: () => ({ setUser: vi.fn(), setStatus: vi.fn() }),
}));

describe('LoginPage', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('tells a blocked user to wait', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 'RATE_LIMITED', detail: 'Too many requests.' }), {
        status: 429,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    render(
      <ThemeProvider><MemoryRouter>
        <LoginPage />
      </MemoryRouter></ThemeProvider>,
    );

    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'ops' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      'Too many sign-in attempts. Please wait a few minutes and try again.',
    );
  });
});
