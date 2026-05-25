import { api } from "@/lib/api";

export type CleanupResponse = {
  removedCount: number;
  message: string;
};

export const adminCleanupService = {
  async accessEvents(confirmation: string) {
    const { data } = await api.post<CleanupResponse>("/api/admin/cleanup/access-events", { confirmation });
    return data;
  },
  async employees(confirmation: string) {
    const { data } = await api.post<CleanupResponse>("/api/admin/cleanup/employees", { confirmation });
    return data;
  }
};
