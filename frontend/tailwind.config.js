/** @type {import('tailwindcss').Config} */

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{js,ts,vue}"],
  theme: {
    container: {
      center: true,
    },
    extend: {
      colors: {
        primary: {
          50: '#FFF5EC',
          100: '#FFE8D1',
          200: '#FFD4A8',
          300: '#FFBF7F',
          400: '#FFAB56',
          500: '#FF8C42',
          600: '#FF6F1E',
          700: '#E65A00',
          800: '#B34500',
          900: '#803000',
        },
        secondary: {
          50: '#F0FBEF',
          100: '#D6F4D4',
          200: '#B0E9AE',
          300: '#8ADE88',
          400: '#6BCB77',
          500: '#4DBF5A',
          600: '#3AA347',
          700: '#2D7F37',
          800: '#215C28',
          900: '#15391A',
        },
        cream: {
          50: '#FFFCF9',
          100: '#FFFAF5',
          200: '#FFF3E6',
          300: '#FFECD2',
          400: '#FFE0B8',
          500: '#FFD49E',
        },
        warm: {
          50: '#FFF9F5',
          100: '#FFF0E6',
          200: '#FFE0CC',
          300: '#FFD1B3',
        },
      },
      backgroundColor: {
        app: '#FFFAF5',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
        '4xl': '2rem',
      },
      boxShadow: {
        'card': '0 4px 20px rgba(255, 140, 66, 0.1)',
        'card-hover': '0 8px 30px rgba(255, 140, 66, 0.15)',
        'soft': '0 2px 12px rgba(0, 0, 0, 0.06)',
        'float': '0 10px 40px rgba(255, 140, 66, 0.2)',
      },
      animation: {
        'fade-in': 'fadeIn 0.5s ease-in-out',
        'slide-up': 'slideUp 0.4s ease-out',
        'slide-down': 'slideDown 0.4s ease-out',
        'bounce-soft': 'bounceSoft 2s infinite',
        'pulse-soft': 'pulseSoft 2s infinite',
        'float': 'float 3s ease-in-out infinite',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(20px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        slideDown: {
          '0%': { opacity: '0', transform: 'translateY(-20px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        bounceSoft: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-10px)' },
        },
        pulseSoft: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.7' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-10px)' },
        },
      },
      backgroundImage: {
        'gradient-primary': 'linear-gradient(135deg, #FF8C42 0%, #FF6F1E 100%)',
        'gradient-secondary': 'linear-gradient(135deg, #6BCB77 0%, #4DBF5A 100%)',
        'gradient-warm': 'linear-gradient(180deg, #FFFAF5 0%, #FFF0E6 100%)',
        'gradient-card': 'linear-gradient(135deg, #FFF5EC 0%, #FFFAF5 100%)',
      },
    },
  },
  plugins: [],
};
