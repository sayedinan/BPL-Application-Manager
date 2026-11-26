import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PresenceConnection } from '@/app/PresenceConnection';
import { RequireAuth } from '@/app/RequireAuth';
import { startLiveStream } from '@/lib/liveStream';

let mustChangePassword = false;

vi.mock('@/auth/AuthContext', () => ({
  useAuth: () => ({
    status: 'authenticated',
    user: { username: 'tester', role: 'ADMIN', mustChangePassword },
  }),
}));
vi.mock('@/lib/liveStream', () => ({ startLiveStream: vi.fn(), stopLiveStream: vi.fn() }));

function show(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<RequireAuth><p>dashboard</p></RequireAuth>} />
        <Route path="/change-password" element={<RequireAuth><p>change form</p></RequireAuth>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('forced password change', () => {
  afterEach(cleanup);
  beforeEach(() => {
    mustChangePassword = false;
    vi.mocked(startLiveStream).mockClear();
  });

  it('sends every other page to the change-password screen', () => {
    mustChangePassword = true;
    show('/');
    expect(screen.getByText('change form')).toBeTruthy();
    expect(screen.queryByText('dashboard')).toBeNull();
  });

  it('leaves other users on the page they asked for', () => {
    show('/');
    expect(screen.getByText('dashboard')).toBeTruthy();
  });

  it('does not open the live stream until the password is changed', () => {
    mustChangePassword = true;
    const view = render(<PresenceConnection />);
    expect(startLiveStream).not.toHaveBeenCalled();

    mustChangePassword = false;
    view.rerender(<PresenceConnection />);
    expect(startLiveStream).toHaveBeenCalledTimes(1);
  });
});
