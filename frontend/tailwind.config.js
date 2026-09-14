/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eef6ff',
          100: '#d9ecff',
          200: '#b7dcff',
          300: '#84c5ff',
          400: '#4aa8ff',
          500: '#1f8bfa',
          600: '#0f6fdb',
          700: '#0f59ad',
          800: '#134a8a',
          900: '#0f2f52',
          950: '#0a1d33',
        },
        surface: {
          light: '#ffffff',
          subtle: '#f6f8fb',
          dark: '#0f1720',
          darkSubtle: '#161f2c',
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
        card: '0 1px 2px 0 rgb(15 23 42 / 0.04), 0 1px 3px 0 rgb(15 23 42 / 0.06)',
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
