/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {},
  },
  // Tailwind `dark:` classes are wired up but not implemented in v1
  // (SPEC §14). Keep the darkMode config so future toggle work has
  // a place to land.
  darkMode: 'class',
  plugins: [],
};
