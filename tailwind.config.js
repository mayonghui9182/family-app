/** @type {import('tailwindcss').Config} */

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    container: {
      center: true,
    },
    extend: {
      colors: {
        primary: {
          50: "#FFF5ED",
          100: "#FFE8D6",
          200: "#FFD0A8",
          300: "#FFB078",
          400: "#FF8C42",
          500: "#FF6B1A",
          600: "#F05508",
          700: "#C74405",
          800: "#9E3709",
          900: "#7F2F0B",
        },
        secondary: {
          50: "#F0FDF4",
          100: "#DCFCE7",
          200: "#BBF7D0",
          300: "#86EFAC",
          400: "#6BCB77",
          500: "#22C55E",
          600: "#16A34A",
          700: "#15803D",
          800: "#166534",
          900: "#14532D",
        },
        cream: {
          50: "#FFFAF5",
          100: "#FFF5EB",
          200: "#FFE8D6",
        },
        warm: {
          50: "#FEFCE8",
          100: "#FEF9C3",
        },
      },
      fontFamily: {
        sans: [
          '"Noto Sans SC"',
          "-apple-system",
          "BlinkMacSystemFont",
          "Segoe UI",
          "Roboto",
          "sans-serif",
        ],
        rounded: [
          '"Quicksand"',
          '"Noto Sans SC"',
          "sans-serif",
        ],
      },
      borderRadius: {
        "2xl": "1rem",
        "3xl": "1.5rem",
        "4xl": "2rem",
      },
      boxShadow: {
        soft: "0 4px 20px -2px rgba(255, 140, 66, 0.15)",
        card: "0 8px 30px -4px rgba(0, 0, 0, 0.08)",
        float: "0 12px 40px -8px rgba(255, 140, 66, 0.25)",
      },
      animation: {
        "float": "float 3s ease-in-out infinite",
        "pulse-slow": "pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite",
        "bounce-soft": "bounce-soft 2s ease-in-out infinite",
        "fade-in": "fadeIn 0.5s ease-out",
        "slide-up": "slideUp 0.4s ease-out",
      },
      keyframes: {
        float: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-10px)" },
        },
        "bounce-soft": {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-5px)" },
        },
        fadeIn: {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" },
        },
        slideUp: {
          "0%": { opacity: "0", transform: "translateY(20px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
    },
  },
  plugins: [],
};
