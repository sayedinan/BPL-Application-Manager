import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Shell } from './Shell';
import { ThemeProvider } from '@/lib/theme';

let user: { username: string; role: string; mustChangePassword: boolean };

vi.mock('@/auth/AuthContext', () => ({
  useAuth: () => ({ status: 'authenticated', user, setUser: () => {}, setStatus: () => {} }),
}));

function show(role: string, mustChangePassword = false, path = '/') {
  user = { username: 'tester', role, mustChangePassword };
  render(
    <ThemeProvider>
      <MemoryRouter initialEntries={[path]}>
        <Shell>
          <p>page body</p>
        </Shell>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

const linkNames = () =>
  Array.from(screen.getByRole('navigation', { name: 'Main' }).querySelectorAll('a')).map((a) => a.textContent);

afterEach(cleanup);

describe('Shell', () => {
  it('shows every section to a Sys.Admin', () => {
    show('SYS_ADMIN');
    expect(linkNames()).toEqual(['Dashboard', 'Logs', 'Applications', 'Users']);
  });

  it('hides Applications from an Admin', () => {
    show('ADMIN');
    expect(linkNames()).toEqual(['Dashboard', 'Logs', 'Users']);
  });

  it('shows a User only the Dashboard, with no empty Manage heading', () => {
    show('USER');
    expect(linkNames()).toEqual(['Dashboard']);
    expect(screen.queryByText('Manage')).toBeNull();
  });

  it('has no navigation while a password change is forced', () => {
    show('SYS_ADMIN', true, '/change-password');
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.getByText('page body')).toBeTruthy();
  });

  it('offers a skip link to the content', () => {
    show('USER');
    expect(screen.getByRole('link', { name: 'Skip to content' }).getAttribute('href')).toBe('#content');
    expect(document.getElementById('content')).toBeTruthy();
  });
});
