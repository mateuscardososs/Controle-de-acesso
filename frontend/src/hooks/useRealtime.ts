"use client";

import { useEffect, useMemo, useState } from "react";
import { RealtimeClient } from "@/services/realtimeService";
import { RealtimeAccessEvent, RealtimeDeviceStatus, SystemAlert } from "@/services/realtimeTypes";

export type RealtimeConnectionStatus = "connecting" | "connected" | "offline" | "error";

export function useRealtime() {
  const [status, setStatus] = useState<RealtimeConnectionStatus>("connecting");
  const [accessEvents, setAccessEvents] = useState<RealtimeAccessEvent[]>([]);
  const [deviceStatuses, setDeviceStatuses] = useState<RealtimeDeviceStatus[]>([]);
  const [systemAlerts, setSystemAlerts] = useState<SystemAlert[]>([]);

  const client = useMemo(
    () =>
      new RealtimeClient({
        onStatusChange: setStatus,
        onAccessEvent: (event) => setAccessEvents((current) => [{ ...event, receivedAt: new Date().toISOString() }, ...current].slice(0, 30)),
        onDeviceStatus: (deviceStatus) => setDeviceStatuses((current) => [deviceStatus, ...current.filter((item) => item.deviceId !== deviceStatus.deviceId)].slice(0, 30)),
        onSystemAlert: (alert) => setSystemAlerts((current) => [{ ...alert, createdAt: alert.createdAt ?? new Date().toISOString() }, ...current].slice(0, 30))
      }),
    []
  );

  useEffect(() => {
    client.connect();
    return () => client.disconnect();
  }, [client]);

  return {
    status,
    connected: status === "connected",
    accessEvents,
    deviceStatuses,
    systemAlerts
  };
}
