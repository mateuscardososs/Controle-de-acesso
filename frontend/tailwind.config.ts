import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}", "./services/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        sport: {
          red: "#8f1218",
          maroon: "#6f0d13",
          graphite: "#111827",
          green: "#15803d"
        }
      }
    }
  },
  plugins: []
};

export default config;
