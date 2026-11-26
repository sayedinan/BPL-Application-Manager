import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApplicationsPage } from '@/app/ApplicationsPage';

vi.mock('@/api/client', () => ({
  ApiError: class extends Error {},
  api: { get: vi.fn(() => Promise.resolve([])) },
}));
vi.mock('@/components/ui/Toast', () => ({ useToast: () => ({ push: vi.fn() }) }));

const flush = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

describe('Add Application form', () => {
  afterEach(cleanup);

  it('forgets what was typed, including the SSH password, once closed', async () => {
    render(<ApplicationsPage />);
    await flush();
    const open = () => fireEvent.click(screen.getAllByRole('button', { name: /add application/i })[0]!);
    const inputs = () => [...document.querySelectorAll<HTMLInputElement>('[role="dialog"] input')];

    open();
    await flush();
    fireEvent.change(inputs()[0]!, { target: { value: 'Old name' } });
    const password = inputs().find((i) => i.type === 'password')!;
    fireEvent.change(password, { target: { value: 'secret' } });

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await flush();
    open();
    await flush();

    expect(inputs().map((i) => i.value).filter(Boolean)).toEqual([]);
  });
});
