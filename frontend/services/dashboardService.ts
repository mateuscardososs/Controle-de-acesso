import { api } from "@/lib/api";
import { AccessEvent } from "./accessEventService";

export type DashboardSummary = {
  totalEmployees: number;
  totalDevices: number;
  todayEvents: number;
  deniedAccesses: number;
};

export type TrafficPeak = {
  hour: number;
  entries: number;
  exits: number;
  passages: number;
  allowed: number;
  denied: number;
};

export const dashboardService = {
  async summary() {
    const { data } = await api.get<DashboardSummary>("/api/dashboard/summary");
    return data;
  },
  async trafficPeaks(date: string) {
    const { data } = await api.get<TrafficPeak[]>("/api/dashboard/traffic-peaks", { params: { date } });
    return data;
  },
  async recentEvents(size = 6) {
    const { data } = await api.get<AccessEvent[]>("/api/dashboard/recent-events", { params: { size } });
    return data;
  }
};
