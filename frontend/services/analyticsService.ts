import { api } from "@/lib/api";

export type AnalyticsFilters = {
  from?: string;
  to?: string;
  deviceId?: string;
  areaId?: string;
  personType?: string;
  releaseMethod?: string;
  granularity?: "HOUR" | "DAY" | "WEEK" | "MONTH";
};

export type OverviewResponse = {
  totalAccesses: number;
  entries: number;
  exits: number;
  uniqueUsers: number;
  visitors: number;
  employees: number;
  facialRecognitions: number;
  cardAccesses: number;
  denials: number;
  successRate: number;
  onlineControllers: number;
  offlineControllers: number;
  avgDwellMinutes: number | null;
  maxDwellMinutes: number | null;
  minDwellMinutes: number | null;
};

export type TimelinePoint = {
  label: string;
  entries: number;
  exits: number;
  total: number;
};

export type HeatmapPoint = {
  dayOfWeek: number;
  dayLabel: string;
  hour: number;
  count: number;
};

export type ControllerStatsItem = {
  id: string;
  name: string;
  areaName: string;
  status: string;
  lastSeenAt: string | null;
  communicationFailures: number;
  totalAccesses: number;
  denials: number;
  lastEvent: string | null;
};

export type AreaStatsItem = {
  id: string;
  name: string;
  totalAccesses: number;
};

export type UserRankItem = {
  personType: string;
  personId: string | null;
  name: string | null;
  cpf: string | null;
  accessCount: number;
};

export type DenialItem = {
  id: string;
  eventTime: string;
  personName: string | null;
  personCpf: string | null;
  deviceName: string | null;
  areaName: string | null;
  decisionReason: string | null;
  releaseMethod: string | null;
};

export type DenialsResponse = {
  total: number;
  byHour: TimelinePoint[];
  recent: DenialItem[];
};

export type PresenceItem = {
  personType: string;
  personId: string | null;
  personName: string | null;
  personCpf: string | null;
  areaName: string;
  entryTime: string;
  minutesInside: number;
};

export type AuthMethodStats = {
  facial: number;
  card: number;
  manual: number;
  other: number;
  total: number;
};

export type PeakItem = { label: string; count: number };

export type PeaksResponse = {
  peakEntry: PeakItem;
  peakExit: PeakItem;
  busiestDay: PeakItem;
  busiestWeek: PeakItem;
  busiestMonth: PeakItem;
};

function compact(f: AnalyticsFilters) {
  return Object.fromEntries(Object.entries(f).filter(([, v]) => v !== undefined && v !== null && v !== ""));
}

export const analyticsService = {
  async overview(f: AnalyticsFilters = {}) {
    const { data } = await api.get<OverviewResponse>("/api/analytics/overview", { params: compact(f) });
    return data;
  },
  async timeline(f: AnalyticsFilters = {}) {
    const { data } = await api.get<TimelinePoint[]>("/api/analytics/timeline", { params: compact(f) });
    return data;
  },
  async heatmap(f: AnalyticsFilters = {}) {
    const { data } = await api.get<HeatmapPoint[]>("/api/analytics/heatmap", { params: compact(f) });
    return data;
  },
  async authMethods(f: AnalyticsFilters = {}) {
    const { data } = await api.get<AuthMethodStats>("/api/analytics/auth-methods", { params: compact(f) });
    return data;
  },
  async controllers(f: AnalyticsFilters = {}) {
    const { data } = await api.get<ControllerStatsItem[]>("/api/analytics/controllers", { params: compact(f) });
    return data;
  },
  async areas(f: AnalyticsFilters = {}) {
    const { data } = await api.get<AreaStatsItem[]>("/api/analytics/areas", { params: compact(f) });
    return data;
  },
  async users(f: AnalyticsFilters = {}) {
    const { data } = await api.get<UserRankItem[]>("/api/analytics/users", { params: compact(f) });
    return data;
  },
  async denials(f: AnalyticsFilters = {}) {
    const { data } = await api.get<DenialsResponse>("/api/analytics/denials", { params: compact(f) });
    return data;
  },
  async presence(f: AnalyticsFilters = {}) {
    const { data } = await api.get<PresenceItem[]>("/api/analytics/presence", { params: compact(f) });
    return data;
  },
  async peaks(f: AnalyticsFilters = {}) {
    const { data } = await api.get<PeaksResponse>("/api/analytics/peaks", { params: compact(f) });
    return data;
  }
};
