import { api } from "@/lib/api";

export type AccessEventFilters = {
  page?: number;
  size?: number;
  startDate?: string;
  endDate?: string;
  personName?: string;
  personCpf?: string;
  invitedDay?: string;
  invitedLounge?: string;
  deviceId?: string;
  areaId?: string;
  eventType?: string;
  accessResult?: "ALLOWED" | "DENIED" | "ERROR" | "";
  recognitionStatus?: string;
  passageStatus?: string;
  releaseMethod?: string;
  origin?: string;
  manualOnly?: boolean;
};

export type AccessEvent = {
  id: string;
  personType: string;
  personId?: string | null;
  personName?: string | null;
  personCpf?: string | null;
  personEmail?: string | null;
  personPhone?: string | null;
  invitedDay?: string | null;
  invitedLounge?: string | null;
  externalUserId?: string | null;
  rawCardName?: string | null;
  deviceId: string;
  deviceName?: string | null;
  areaId: string;
  areaName?: string | null;
  eventType: string;
  accessResult: "ALLOWED" | "DENIED" | "ERROR";
  eventCategory?: string | null;
  recognitionStatus?: string | null;
  passageStatus?: string | null;
  releaseMethod?: string | null;
  operatorUserId?: string | null;
  manualReason?: string | null;
  controllerMethod?: string | null;
  controllerDoor?: string | null;
  controllerReaderId?: string | null;
  controllerRecNo?: string | null;
  decisionReason?: string | null;
  eventTime: string;
  occurredAt?: string | null;
  origin: string;
  rawPayload?: Record<string, unknown>;
  cooldownBlocked?: boolean;
  cooldownReason?: string | null;
};

export type AccessEventPage = {
  content: AccessEvent[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type ManualReleasePayload = {
  personName: string;
  personCpf?: string;
  deviceId: string;
  reason: string;
  operatorObservation?: string;
};

function compactParams(filters: AccessEventFilters = {}) {
  return Object.fromEntries(
    Object.entries(filters).filter(([, value]) => value !== undefined && value !== null && value !== "")
  );
}

function normalizePage(data: AccessEvent[] | AccessEventPage, filters: AccessEventFilters): AccessEventPage {
  if (Array.isArray(data)) {
    return {
      content: data,
      page: filters.page ?? 0,
      size: filters.size ?? data.length,
      totalElements: data.length,
      totalPages: data.length === 0 ? 0 : 1
    };
  }
  return data;
}

export const accessEventService = {
  async listAccessEvents(filters: AccessEventFilters = {}) {
    const params = compactParams(filters);
    const { data } = await api.get<AccessEvent[] | AccessEventPage>("/api/access-events", { params });
    return normalizePage(data, filters);
  },
  async list() {
    const { content } = await this.listAccessEvents({ page: 0, size: 50 });
    return content;
  },
  async createManualRelease(payload: ManualReleasePayload) {
    const { data } = await api.post<AccessEvent>("/api/access-events/manual-release", payload);
    return data;
  },
  async exportCsv(filters: AccessEventFilters = {}) {
    const params = compactParams(filters);
    const response = await api.get<Blob>("/api/access-events/export.csv", {
      params,
      responseType: "blob"
    });
    const disposition = response.headers["content-disposition"];
    const match = typeof disposition === "string" ? disposition.match(/filename="?([^";]+)"?/i) : null;
    return {
      blob: response.data,
      filename: match?.[1] ?? `eventos-acesso-${new Date().toISOString().slice(0, 10)}.csv`
    };
  }
};
