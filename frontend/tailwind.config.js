/** @type {import('tailwindcss').Config} */

// Semantic tokens live in src/styles/index.css as space-separated RGB channels
// (:root for light, html.dark for dark), so one class works in both themes and
// still supports alpha modifiers such as bg-canvas/60.
const token = (name) => `rgb(var(--${name}) / <alpha-value>)`;

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // ---- Semantic tokens (preferred for all new and restyled UI) ----
        canvas: token('canvas'), // page background
        panel: token('panel'), // raised surfaces: cards, modals, table bodies
        sunken: token('sunken'), // inset surfaces: table headers, code, inputs
        ink: token('ink'), // primary text
        muted: token('muted'), // secondary text (>= 4.5:1 on canvas and panel)
        rule: token('rule'), // card borders and dividers
        field: token('field'), // input and control borders (>= 3:1)
        accent: token('accent'), // brand blue, interactive
        ok: { DEFAULT: token('ok'), bg: token('ok-bg') },
        warn: { DEFAULT: token('warn'), bg: token('warn-bg') },
        danger: { DEFAULT: token('danger'), bg: token('danger-bg') },
        idle: { DEFAULT: token('idle'), bg: token('idle-bg') },
        maint: { DEFAULT: token('maint'), bg: token('maint-bg') },
        brand: {
          50: '#f0f4fb',
          100: '#dfe7f6',
          200: '#c3d2ee',
          300: '#9bb3e2',
          400: '#7190d0',
          500: '#5379c0',
          600: '#466ab3', // logo blue; primary actions
          700: '#3a5793',
          800: '#324a79',
          900: '#1f3157',
          950: '#0e1a33', // sidebar navy
        },
      },
      fontFamily: {
        sans: [
          '"Public Sans Variable"',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'sans-serif',
        ],
        mono: [
          '"JetBrains Mono Variable"',
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'Consolas',
          'monospace',
        ],
      },
      borderRadius: {
        xl: '0.625rem',
        '2xl': '0.875rem',
      },
      boxShadow: {
        // A tight contact shadow plus a wide soft one: cards sit above the page.
        card: '0 1px 2px 0 rgb(14 26 51 / 0.10), 0 6px 12px -6px rgb(14 26 51 / 0.14), 0 22px 40px -22px rgb(14 26 51 / 0.38)',
        // Dark mode: a lit top edge and a deeper drop.
        'card-dark': 'inset 0 1px 0 0 rgb(255 255 255 / 0.06), 0 18px 36px -18px rgb(0 0 0 / 0.75)',
        hero: '0 2px 4px 0 rgb(14 26 51 / 0.18), 0 24px 44px -22px rgb(14 26 51 / 0.65)',
        'card-hover': '0 2px 4px 0 rgb(14 26 51 / 0.12), 0 12px 20px -8px rgb(14 26 51 / 0.20), 0 28px 48px -22px rgb(14 26 51 / 0.45)',
        popover: '0 16px 40px -10px rgb(14 26 51 / 0.34)',
      },
      keyframes: {
        rise: {
          from: { opacity: 0, transform: 'translateY(6px)' },
          to: { opacity: 1, transform: 'translateY(0)' },
        },
        pulseSoft: {
          '0%, 100%': { opacity: 1 },
          '50%': { opacity: 0.55 },
        },
        fadeIn: {
          from: { opacity: 0, transform: 'translateY(4px)' },
          to: { opacity: 1, transform: 'translateY(0)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '200% 0' },
          '100%': { backgroundPosition: '-200% 0' },
        },
      },
      animation: {
        rise: 'rise 0.35s cubic-bezier(0.2, 0.7, 0.2, 1) both',
        'pulse-soft': 'pulseSoft 1.8s ease-in-out infinite',
        'fade-in': 'fadeIn 0.18s ease-out',
        shimmer: 'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
};
