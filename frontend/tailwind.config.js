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
        rule: token('rule'), // borders and dividers
        accent: token('accent'), // brand blue, interactive
        ok: { DEFAULT: token('ok'), bg: token('ok-bg') },
        warn: { DEFAULT: token('warn'), bg: token('warn-bg') },
        danger: { DEFAULT: token('danger'), bg: token('danger-bg') },
        idle: { DEFAULT: token('idle'), bg: token('idle-bg') },
        maint: { DEFAULT: token('maint'), bg: token('maint-bg') },
        brand: {
          50: '#eef6ff',
          100: '#d9ecff',
          200: '#b7dcff',
          300: '#84c5ff',
          400: '#4aa8ff',
          500: '#1f8bfa',
          600: '#0f6fdb', // primary action color
          700: '#0f59ad',
          800: '#134a8a',
          900: '#0f2f52', // logo navy
          950: '#0a1d33',
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
        card: '0 1px 2px 0 rgb(14 27 44 / 0.04), 0 2px 8px -2px rgb(14 27 44 / 0.06)',
        'card-hover': '0 2px 6px -1px rgb(14 27 44 / 0.10), 0 1px 2px -1px rgb(14 27 44 / 0.06)',
        popover: '0 10px 30px -5px rgb(14 27 44 / 0.22)',
      },
      keyframes: {
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
        'pulse-soft': 'pulseSoft 1.8s ease-in-out infinite',
        'fade-in': 'fadeIn 0.18s ease-out',
        shimmer: 'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
};
