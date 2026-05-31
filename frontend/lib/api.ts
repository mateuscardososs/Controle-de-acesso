import axios from "axios";

export const TOKEN_KEY = "access_control_token";

const apiBaseUrl = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");

export const api = axios.create({
  baseURL: apiBaseUrl
});

api.interceptors.request.use((config) => {
  if (typeof window !== "undefined") {
    const token = window.localStorage.getItem(TOKEN_KEY);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (typeof window !== "undefined" && error.response?.status === 401) {
      const publicPaths = ["/", "/login", "/guest-registration", "/register/employee", "/invite", "/checkin"];
      const isPublicPage = publicPaths.some((path) => window.location.pathname === path || window.location.pathname.startsWith(`${path}/`));
      window.localStorage.removeItem(TOKEN_KEY);
      if (!isPublicPage) {
        window.location.assign("/login");
      }
    }
    return Promise.reject(error);
  }
);
