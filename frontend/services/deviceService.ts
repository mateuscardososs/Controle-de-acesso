import { api } from "@/lib/api";

export type Device = {
  id: string;
  name: string;
  model?: string;
  serialNumber?: string;
  ipAddress: string;
  location?: string;
  operationType: string;
  status: string;
  areaId?: string;
  areaName: string;
  lastSeenAt?: string;
  lastHeartbeatAt?: string;
  communicationFailures?: number;
  onlineStatus?: string;
};

export type DevicePayload = {
  name: string;
  model?: string;
  serialNumber?: string;
  ipAddress: string;
  location?: string;
  operationType: "ENTRY" | "EXIT" | "ENTRY_EXIT";
  status: "ONLINE" | "OFFLINE" | "UNKNOWN";
  areaId: string;
};

export const deviceService = {
  async list() {
    const { data } = await api.get<Device[]>("/api/devices");
    return data;
  },
  async create(payload: DevicePayload) {
    const { data } = await api.post<Device>("/api/devices", payload);
    return data;
  }
};
