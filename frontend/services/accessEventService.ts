import { api } from "@/lib/api";

export type AccessEvent = {
  id: string;
  personType: string;
  personId?: string | null;
  personName?: string | null;
  personCpf?: string | null;
  externalUserId?: string | null;
  rawCardName?: string | null;
  deviceId: string;
  areaId: string;
  eventType: string;
  accessResult: "ALLOWED" | "DENIED" | "ERROR";
  eventTime: string;
  origin: string;
  rawPayload?: Record<string, unknown>;
};

export const accessEventService = {
  async list() {
    const { data } = await api.get<AccessEvent[]>("/api/access-events");
    return data;
  }
};
