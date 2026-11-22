import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatUptime } from './duration';

const NOW = new Date('2026-11-23T12:00:00Z');
const ago = (seconds: number) => new Date(NOW.getTime() - seconds * 1000).toISOString();

describe('formatUptime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => vi.useRealTimers());

  it('shows days and hours for long uptimes', () => {
    expect(formatUptime(ago(3 * 86400 + 4 * 3600 + 59))).toBe('3d 4h');
  });

  it('shows hours and minutes under a day', () => {
    expect(formatUptime(ago(19 * 3600 + 2 * 60))).toBe('19h 2m');
  });

  it('shows minutes only under an hour', () => {
    expect(formatUptime(ago(40 * 60 + 38))).toBe('40m');
  });

  it('says "<1m" for a brand-new start', () => {
    expect(formatUptime(ago(12))).toBe('<1m');
  });

  it('returns null when there is no start time or it is in the future', () => {
    expect(formatUptime(null)).toBeNull();
    expect(formatUptime(new Date(NOW.getTime() + 60000).toISOString())).toBeNull();
  });
});
