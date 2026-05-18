export type RealtimeAccessEvent = {
  accessEventId?: string;
  id?: string;
  personType?: string;
  personId?: string;
  personName?: string | null;
  personCpf?: string | null;
  deviceId?: string;
  deviceName?: string | null;
  areaId?: string;
  areaName?: string | null;
  eventType?: "ENTRY" | "EXIT" | "ACCESS_DENIED" | "COMMUNICATION_FAILURE" | string;
  accessResult?: "ALLOWED" | "DENIED" | "ERROR" | string;
  eventTime?: string;
  origin?: string;
  receivedAt?: string;
};

export type RealtimeDeviceStatus = {
  deviceId: string;
  status: "ONLINE" | "OFFLINE" | "UNKNOWN" | string;
  lastSeenAt?: string;
  message?: string;
};

export type SystemAlert = {
  id?: string;
  severity?: "INFO" | "WARNING" | "ERROR" | "CRITICAL" | string;
  level?: "INFO" | "WARNING" | "ERROR" | "CRITICAL" | string;
  title?: string;
  message?: string;
  createdAt?: string;
  source?: string;
};
