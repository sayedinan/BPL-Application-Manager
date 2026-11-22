import { describe, expect, it } from 'vitest';
import { isNearBottom } from './scroll';

describe('isNearBottom', () => {
  it('is true when fully scrolled to the bottom', () => {
    // 1000px of content in a 400px box: the largest possible scrollTop is 600.
    expect(isNearBottom({ scrollTop: 600, scrollHeight: 1000, clientHeight: 400 })).toBe(true);
  });

  it('is true within the threshold of the bottom', () => {
    expect(isNearBottom({ scrollTop: 560, scrollHeight: 1000, clientHeight: 400 })).toBe(true);
  });

  it('is false once the user scrolls up to read older lines', () => {
    expect(isNearBottom({ scrollTop: 300, scrollHeight: 1000, clientHeight: 400 })).toBe(false);
  });

  it('is true when the content is shorter than the box', () => {
    expect(isNearBottom({ scrollTop: 0, scrollHeight: 200, clientHeight: 400 })).toBe(true);
  });
});
