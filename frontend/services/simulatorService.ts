import { api } from "@/lib/api";
import { AccessEvent } from "./accessEventService";

export type AccessEventSimulatorPayload = {
  cpf: string;
  deviceId: string;
  eventType: "ENTRY" | "EXIT" | "ACCESS_DENIED" | "COMMUNICATION_FAILURE";
  result: "ALLOWED" | "DENIED" | "ERROR";
};

export const simulatorService = {
  async accessEvent(payload: AccessEventSimulatorPayload) {
    const { data } = await api.post<AccessEvent>("/api/simulator/access-event", payload);
    return data;
  }
};
