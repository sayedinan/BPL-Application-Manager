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

        // ---- Legacy names, remapped onto the cool palette ----
        // Untouched pages still use slate-*, gh-*, surface-* and status-*.
        // Retire these as each page is restyled onto the semantic tokens above.
        slate: {
          50: '#f4f6f9',
          100: '#eaeef3',
          200: '#d5dae1',
          300: '#b3bcc9',
          400: '#8b97a8',
          500: '#66738a',
          600: '#4d5a70',
          700: '#3a475b',
          800: '#25324a',
          900: '#0e1b2c',
          950: '#08121f',
        },
        // Brand palette pulled from the BPL logo (navy hexagon + cyan power ring).
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
        surface: {
          light: token('panel'),
          subtle: token('sunken'),
          canvas: token('canvas'),
          dark: '#08172a',
          darkSubtle: '#0d2038',
        },
        // Dark palette derived from the logo navy (no pure black, no GitHub gray).
        gh: {
          inset: '#061120',
          subtle: '#0d2038',
          hover: '#14304f',
          border: '#1f3550',
          fg: '#e6eef8',
          fgSoft: '#c5d3e4',
          muted: '#9db0c7',
          subtle2: '#7a8fa8',
        },
        status: {
          online: '#15803d',
          onlineBg: '#e3f5e9',
          offline: '#4b5563',
          offlineBg: '#eef0f3',
          pending: '#b45309',
          pendingBg: '#fef3c7',
          error: '#b91c1c',
          errorBg: '#fee2e2',
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
