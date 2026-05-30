import axios from "axios";
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

type ApiErrorResponse = {
  status?: number;
  error?: string;
  details?: string[];
};

export const authService = {
  async login(email: string, password: string) {
    const payload = { email: email.trim().toLowerCase(), password };
    if (typeof window !== "undefined") {
      window.localStorage.removeItem(TOKEN_KEY);
    }
    logLoginDebug("LOGIN REQUEST", { email: payload.email, password: maskPassword(payload.password) });
    try {
      const { data } = await api.post<LoginResponse>("/api/auth/login", payload);
      logLoginDebug("LOGIN RESPONSE", {
        ...data,
        accessToken: maskToken(data.accessToken)
      });
      if (typeof window !== "undefined") {
        window.localStorage.setItem(TOKEN_KEY, data.accessToken);
      }
      return data;
    } catch (error) {
      logLoginDebug("LOGIN ERROR", error, true);
      throw new Error(loginErrorMessage(error));
    }
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

function loginErrorMessage(error: unknown) {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    const details = error.response?.data?.details?.filter(Boolean);
    if (details?.length) {
      return details.join(" ");
    }
    if (error.response?.status === 401) {
      return "Credenciais invalidas.";
    }
    if (error.response?.status) {
      return `Falha no login (${error.response.status}).`;
    }
    return "Nao foi possivel conectar ao backend.";
  }
  return "Credenciais invalidas ou sessao indisponivel.";
}

function logLoginDebug(label: string, value: unknown, error = false) {
  if (process.env.NODE_ENV === "production") {
    return;
  }
  if (error) {
    console.error(label, value);
    return;
  }
  console.log(label, value);
}

function maskPassword(password: string) {
  return password ? "********" : "";
}

function maskToken(token: string) {
  if (!token) {
    return "";
  }
  return `${token.slice(0, 12)}...${token.slice(-8)}`;
}
