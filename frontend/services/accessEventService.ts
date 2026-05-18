import { api } from "@/lib/api";

export type AccessEvent = {
  id: string;
  personType: string;
  personId: string;
  deviceId: string;
  areaId: string;
  eventType: string;
  accessResult: "ALLOWED" | "DENIED";
  eventTime: string;
  origin: string;
};

export const accessEventService = {
  async list() {
    const { data } = await api.get<AccessEvent[]>("/api/access-events");
    return data;
  }
};
