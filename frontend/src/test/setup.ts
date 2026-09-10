// Test setup — runs before each test file.
//
// Vitest's default globals are off (per vitest.config.ts), so the
// describe/it/expect/etc. used by tests are imported from 'vitest'
// at the top of each test file. This file is for cross-test setup:
// environment configuration, mocks that need to be in place before
// any test runs, etc.

// ResizeObserver is referenced by some React libs but not provided
// by jsdom. Stub it so component tests that render anything using
// ResizeObserver don't crash.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
// @ts-ignore — assigning a stub to a readonly global
globalThis.ResizeObserver = ResizeObserverStub;

// matchMedia is similarly missing from jsdom. Some component
// libraries (Tailwind, Headless UI) call it on mount.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});
