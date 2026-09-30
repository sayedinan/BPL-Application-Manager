/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // GitHub (Primer) light neutrals. Overrides Tailwind's default slate,
        // so every existing slate-* class in light mode becomes GitHub gray.
        slate: {
          50: '#f6f8fa',
          100: '#eaeef2',
          200: '#d0d7de',
          300: '#afb8c1',
          400: '#8c959f',
          500: '#6e7781',
          600: '#57606a',
          700: '#424a53',
          800: '#32383f',
          900: '#1f2328',
          950: '#0d1117',
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
          light: '#ffffff',
          subtle: '#ffffff',
          canvas: '#ffffff',
          dark: '#0d1117',
          darkSubtle: '#161b22',
        },
        // GitHub (Primer) dark palette - neutral, no blue tint
        gh: {
          inset: '#010409',
          subtle: '#161b22',
          hover: '#21262d',
          border: '#30363d',
          fg: '#e6edf3',
          fgSoft: '#c9d1d9',
          muted: '#8d96a0',
          subtle2: '#6e7681',
        },
        status: {
          online: '#16a34a',
          onlineBg: '#dcfce7',
          offline: '#6b7280',
          offlineBg: '#f1f2f4',
          pending: '#d97706',
          pendingBg: '#fef3c7',
          error: '#dc2626',
          errorBg: '#fee2e2',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        xl: '0.875rem',
        '2xl': '1.25rem',
      },
      boxShadow: {
        card: '0 1px 2px 0 rgb(15 23 42 / 0.03), 0 4px 16px -4px rgb(15 23 42 / 0.08)',
        'card-hover': '0 4px 12px -2px rgb(15 23 42 / 0.10), 0 2px 4px -2px rgb(15 23 42 / 0.06)',
        popover: '0 10px 30px -5px rgb(15 23 42 / 0.20)',
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
      },
      animation: {
        'pulse-soft': 'pulseSoft 1.8s ease-in-out infinite',
        'fade-in': 'fadeIn 0.18s ease-out',
      },
    },
  },
  plugins: [],
};
