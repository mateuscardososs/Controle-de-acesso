"use client";

import { TOKEN_KEY } from "@/lib/api";
import { IntegrationSyncEvent, RealtimeAccessEvent, RealtimeDeviceStatus, SystemAlert } from "./realtimeTypes";

type RealtimeStatus = "connecting" | "connected" | "offline" | "error";

type RealtimeHandlers = {
  onAccessEvent?: (event: RealtimeAccessEvent) => void;
  onDeviceStatus?: (status: RealtimeDeviceStatus) => void;
  onSystemAlert?: (alert: SystemAlert) => void;
  onIntegrationSync?: (event: IntegrationSyncEvent) => void;
  onStatusChange?: (status: RealtimeStatus) => void;
};

type Subscription = {
  id: string;
  destination: string;
  callback: (payload: unknown) => void;
};

const topics = {
  accessEvents: "/topic/access-events",
  deviceStatus: "/topic/device-status",
  systemAlerts: "/topic/system-alerts",
  integrationSync: "/topic/integration-sync"
};

function apiBaseUrl() {
  return process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
}

function websocketUrl() {
  const explicitUrl = process.env.NEXT_PUBLIC_WS_URL;
  if (explicitUrl) return explicitUrl;

  const base = apiBaseUrl();
  const url = new URL(base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws";
  url.search = "";
  return url.toString();
}

function stompFrame(command: string, headers: Record<string, string> = {}, body = "") {
  const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`);
  return `${command}\n${headerLines.join("\n")}\n\n${body}\0`;
}

function parseFrame(rawFrame: string) {
  const cleanFrame = rawFrame.replace(/\0$/, "");
  const [headerPart, body = ""] = cleanFrame.split("\n\n");
  const [command, ...headerLines] = headerPart.split("\n");
  const headers = Object.fromEntries(
    headerLines.filter(Boolean).map((line) => {
      const separator = line.indexOf(":");
      return [line.slice(0, separator), line.slice(separator + 1)];
    })
  );
  return { command, headers, body };
}

function parseBody(body: string) {
  if (!body) return {};
  try {
    return JSON.parse(body) as unknown;
  } catch {
    return { message: body };
  }
}

export class RealtimeClient {
  private socket?: WebSocket;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private reconnectAttempts = 0;
  private manuallyClosed = false;
  private subscriptions: Subscription[] = [];

  constructor(private handlers: RealtimeHandlers) {}

  connect() {
    if (typeof window === "undefined") return;
    this.manuallyClosed = false;
    this.handlers.onStatusChange?.("connecting");
    this.openSocket();
  }

  disconnect() {
    this.manuallyClosed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(stompFrame("DISCONNECT"));
    }
    this.socket?.close();
  }

  private openSocket() {
    this.socket = new WebSocket(websocketUrl(), ["v12.stomp", "v11.stomp", "v10.stomp"]);

    this.socket.onopen = () => {
      const token = window.localStorage.getItem(TOKEN_KEY);
      this.socket?.send(
        stompFrame("CONNECT", {
          "accept-version": "1.2",
          "heart-beat": "10000,10000",
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        })
      );
    };

    this.socket.onmessage = (message) => this.handleMessage(String(message.data));
    this.socket.onerror = () => this.handlers.onStatusChange?.("error");
    this.socket.onclose = () => {
      if (this.manuallyClosed) {
        this.handlers.onStatusChange?.("offline");
        return;
      }
      this.handlers.onStatusChange?.("offline");
      this.scheduleReconnect();
    };
  }

  private handleMessage(data: string) {
    for (const chunk of data.split("\0")) {
      if (!chunk.trim()) continue;
      const frame = parseFrame(`${chunk}\0`);

      if (frame.command === "CONNECTED") {
        this.reconnectAttempts = 0;
        this.handlers.onStatusChange?.("connected");
        this.subscribeAll();
      }

      if (frame.command === "MESSAGE") {
        const subscription = this.subscriptions.find((item) => item.id === frame.headers.subscription);
        subscription?.callback(parseBody(frame.body));
      }

      if (frame.command === "ERROR") {
        this.handlers.onStatusChange?.("error");
      }
    }
  }

  private subscribeAll() {
    this.subscriptions = [
      { id: "access-events", destination: topics.accessEvents, callback: (payload) => this.handlers.onAccessEvent?.(payload as RealtimeAccessEvent) },
      { id: "device-status", destination: topics.deviceStatus, callback: (payload) => this.handlers.onDeviceStatus?.(payload as RealtimeDeviceStatus) },
      { id: "system-alerts", destination: topics.systemAlerts, callback: (payload) => this.handlers.onSystemAlert?.(payload as SystemAlert) },
      { id: "integration-sync", destination: topics.integrationSync, callback: (payload) => this.handlers.onIntegrationSync?.(payload as IntegrationSyncEvent) }
    ];

    for (const subscription of this.subscriptions) {
      this.socket?.send(
        stompFrame("SUBSCRIBE", {
          id: subscription.id,
          destination: subscription.destination,
          ack: "auto"
        })
      );
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 15000);
    this.reconnectAttempts += 1;
    this.reconnectTimer = setTimeout(() => this.openSocket(), delay);
  }
}
