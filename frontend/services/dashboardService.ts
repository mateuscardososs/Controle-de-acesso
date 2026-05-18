import { api } from "@/lib/api";

export type DashboardSummary = {
  totalEmployees: number;
  totalDevices: number;
  todayEvents: number;
  deniedAccesses: number;
};

export const dashboardService = {
  async summary() {
    const { data } = await api.get<DashboardSummary>("/api/dashboard/summary");
    return data;
  }
};
