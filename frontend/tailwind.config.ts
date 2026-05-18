import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}", "./services/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        sport: {
          red: "#b91c1c",
          graphite: "#111827",
          green: "#15803d"
        }
      }
    }
  },
  plugins: []
};

export default config;
