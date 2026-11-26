// WCAG 2.2 AA contrast guard for the design tokens in index.css.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(resolve(process.cwd(), 'src/styles/index.css'), 'utf8');

function tokens(selector: string): Record<string, number[]> {
  const start = css.indexOf(`\n${selector} {`);
  const block = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries(
    [...block.matchAll(/--([\w-]+):\s*(\d+) (\d+) (\d+);/g)].map((m) => [m[1], [+m[2], +m[3], +m[4]]]),
  );
}

function luminance(rgb: number[]): number {
  const [r, g, b] = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a: number[], b: number[]): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe.each([':root', 'html.dark'])('%s tokens', (selector) => {
  const t = tokens(selector);
  const pairs: [string, string, number][] = [
    ...['ink', 'muted', 'accent', 'ok', 'warn', 'danger'].flatMap((fg) =>
      ['canvas', 'panel', 'sunken'].map((bg): [string, string, number] => [fg, bg, 4.5]),
    ),
    ...['ok', 'warn', 'danger', 'idle', 'maint'].map((s): [string, string, number] => [s, `${s}-bg`, 4.5]),
    ['field', 'panel', 3],
    ['field', 'canvas', 3],
  ];

  it.each(pairs)('%s on %s is at least %s:1', (fg, bg, min) => {
    expect(t[fg], fg).toBeDefined();
    expect(t[bg], bg).toBeDefined();
    expect(ratio(t[fg], t[bg])).toBeGreaterThanOrEqual(min);
  });
});
