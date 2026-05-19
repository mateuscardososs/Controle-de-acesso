import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
    "./services/**/*.{ts,tsx}",
    "./src/**/*.{ts,tsx}"
  ],
  theme: {
    extend: {
      colors: {
        shell: {
          950: "#0B1020",
          900: "#111827",
          850: "#151D2E",
          800: "#1A2236",
          700: "#242E45"
        },
        brand: {
          red: "#9A1B2E",
          maroon: "#7F1424",
          wine: "#B43A4B",
          graphite: "#111827",
          green: "#15803d"
        }
      },
      boxShadow: {
        enterprise: "0 22px 70px rgba(0, 0, 0, 0.28)",
        glow: "0 0 0 1px rgba(255,255,255,0.06), 0 18px 60px rgba(0,0,0,0.34)"
      },
      borderRadius: {
        enterprise: "18px"
      }
    }
  },
  plugins: []
};

export default config;
