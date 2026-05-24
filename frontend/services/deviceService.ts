import { api } from "@/lib/api";

export type Device = {
  id: string;
  name: string;
  model?: string;
  serialNumber?: string;
  ipAddress: string;
  httpPort?: number;
  intelbrasUsername?: string;
  intelbrasPasswordConfigured?: boolean;
  location?: string;
  operationType: string;
  status: string;
  areaId?: string;
  areaName: string;
  lastSeenAt?: string;
  lastHeartbeatAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastError?: string;
  communicationFailures?: number;
  onlineStatus?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type DevicePayload = {
  name: string;
  model: string;
  serialNumber?: string;
  ipAddress: string;
  httpPort?: number;
  intelbrasUsername?: string;
  intelbrasPassword?: string;
  location?: string;
  operationType: "ENTRY" | "EXIT" | "ENTRY_EXIT";
  status: "ONLINE" | "OFFLINE" | "MAINTENANCE" | "UNKNOWN";
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
  },
  async update(id: string, payload: DevicePayload) {
    const { data } = await api.put<Device>(`/api/devices/${id}`, payload);
    return data;
  },
  async delete(id: string) {
    await api.delete(`/api/devices/${id}`);
  },
  async ping(id: string) {
    const { data } = await api.post<Device>(`/api/devices/${id}/ping`);
    return data;
  }
};
