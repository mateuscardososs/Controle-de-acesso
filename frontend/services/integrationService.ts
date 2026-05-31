import { api } from "@/lib/api";

export type IntelbrasIntegrationStatus = {
  mode: "real" | "fake" | string;
  syncEnabled: boolean;
};

export type IntelbrasEventImportResult = {
  deviceId?: string | null;
  received: number;
  imported: number;
  skipped: number;
  devicesScanned: number;
};

export type BulkSyncResult = {
  status: string;
  guestsQueued: number;
  employeesQueued: number;
  totalQueued: number;
  guestsSkipped: number;
  employeesSkipped: number;
};

export const integrationService = {
  async intelbrasStatus() {
    const { data } = await api.get<IntelbrasIntegrationStatus>("/api/integration/intelbras/status");
    return data;
  },
  async importIntelbrasEventsNow() {
    const { data } = await api.post<IntelbrasEventImportResult>("/api/admin/intelbras/events/import");
    return data;
  },
  async importIntelbrasDeviceEvents(deviceId: string) {
    const { data } = await api.post<IntelbrasEventImportResult>(`/api/admin/intelbras/devices/${deviceId}/events/import`);
    return data;
  },
  async retryBulk() {
    const { data } = await api.post<BulkSyncResult>("/api/integration/retry/bulk");
    return data;
  }
};
