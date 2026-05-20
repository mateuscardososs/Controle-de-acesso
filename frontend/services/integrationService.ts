import { api } from "@/lib/api";

export type IntelbrasIntegrationStatus = {
  mode: "real" | "fake" | string;
  syncEnabled: boolean;
};

export const integrationService = {
  async intelbrasStatus() {
    const { data } = await api.get<IntelbrasIntegrationStatus>("/api/integration/intelbras/status");
    return data;
  }
};
