import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LogsBox } from '@/app/LogsBox';

interface FakeClient {
  connected: boolean;
  reconnectDelay: number;
  onConnect?: () => void;
  onWebSocketClose?: () => void;
  deliver?: (body: string) => void;
  activate: () => void;
  deactivate: () => void;
  subscribe: (topic: string, handler: (message: { body: string }) => void) => void;
}

const clients: FakeClient[] = [];

vi.mock('@stomp/stompjs', () => ({
  ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 },
  Client: class {
    connected = false;
    reconnectDelay = 5000;
    onConnect?: () => void;
    onWebSocketClose?: () => void;
    deliver?: (body: string) => void;
    constructor(options: Record<string, unknown>) {
      Object.assign(this, options);
      clients.push(this as unknown as FakeClient);
    }
    activate() {}
    deactivate() {}
    subscribe(_topic: string, handler: (message: { body: string }) => void) {
      this.deliver = (body) => handler({ body });
    }
  },
}));
vi.mock('@/api/client', () => ({
  ApiError: class extends Error {},
  api: {
    get: vi.fn((path: string) =>
      Promise.resolve(path.endsWith('/logs') ? [] : [{ id: 1, name: 'orders' }]),
    ),
  },
}));
vi.mock('@/components/ui/Toast', () => ({ useToast: () => ({ push: vi.fn() }) }));

const client = () => clients[clients.length - 1]!;

async function open() {
  const view = render(<LogsBox role="USER" />);
  // Audit is not available to a USER, so choose the application log.
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
  const select = screen.getByLabelText('Log source') as HTMLSelectElement;
  const { fireEvent } = await import('@testing-library/react');
  await act(async () => {
    fireEvent.change(select, { target: { value: 'app-1' } });
    await vi.advanceTimersByTimeAsync(0);
  });
  return view;
}

function connect() {
  act(() => {
    client().connected = true;
    client().onConnect?.();
  });
}

const status = () => screen.getAllByRole('status')[0]!.textContent;

describe('LogsBox live connection', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    clients.length = 0;
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('stays Live after connecting', async () => {
    await open();
    connect();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(status()).toBe('Live');
  });

  it('says Reconnecting after a drop, backs off, and returns to Live', async () => {
    await open();
    connect();

    act(() => client().onWebSocketClose?.());
    expect(status()).toBe('Reconnecting…');
    // SPEC §5.3: 1, 2, 4, 8, 16 then 30 seconds, using the library's own backoff.
    expect(client()).toMatchObject({ reconnectDelay: 1000, maxReconnectDelay: 30000, reconnectTimeMode: 1 });

    connect();
    expect(status()).toBe('Live');
  });

  it('says Disconnected after ten failed attempts', async () => {
    await open();
    for (let i = 0; i < 11; i++) act(() => client().onWebSocketClose?.());
    expect(status()).toBe('Disconnected');
  });

  it('keeps only the latest 500 lines', async () => {
    await open();
    connect();
    act(() => {
      for (let i = 1; i <= 520; i++) client().deliver?.(JSON.stringify({ content: `line-${i}` }));
    });
    const lines = screen.getByLabelText('Application log output').textContent!.trim().split('\n');
    expect(lines).toHaveLength(500);
    expect(lines[0]).toBe('line-21');
    expect(lines[499]).toBe('line-520');
  });
});
