import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}"
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          red: "#9A1B2E",
          wine: "#B43A4B",
          ink: "#1D2530",
          mint: "#0F766E"
        }
      },
      boxShadow: {
        panel: "0 20px 60px rgba(29, 37, 48, 0.12)"
      }
    }
  },
  plugins: []
};

export default config;
