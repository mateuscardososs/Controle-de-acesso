import { api, TOKEN_KEY } from "@/lib/api";

export type Role = "ADMIN" | "HR" | "SECURITY_VIEWER";

export type CurrentUser = {
  id: string;
  name: string;
  email: string;
  role: Role;
};

export type LoginResponse = {
  accessToken: string;
  tokenType: "Bearer";
  expiresInSeconds: number;
  user: CurrentUser;
};

export const authService = {
  async login(email: string, password: string) {
    const { data } = await api.post<LoginResponse>("/api/auth/login", { email, password });
    window.localStorage.setItem(TOKEN_KEY, data.accessToken);
    return data;
  },
  async me() {
    const { data } = await api.get<CurrentUser>("/api/auth/me");
    return data;
  },
  logout() {
    window.localStorage.removeItem(TOKEN_KEY);
    window.location.assign("/login");
  }
};
