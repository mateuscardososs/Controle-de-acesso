import { api } from "@/lib/api";

export const configService = {
  async lounges() {
    const { data } = await api.get<{ lounges: string[] }>("/api/config/lounges");
    return data.lounges;
  }
};
