import { Activity, ShieldAlert } from "lucide-react";
import { AccessEvent } from "@/services/accessEventService";
import { RealtimeAccessEvent } from "@/services/realtimeTypes";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { EmptyState } from "./AsyncState";
import { StatusBadge } from "./StatusBadge";

function eventId(event: AccessEvent | RealtimeAccessEvent) {
  return "id" in event && event.id ? event.id : "accessEventId" in event ? event.accessEventId : undefined;
}

function eventResult(event: AccessEvent | RealtimeAccessEvent) {
  return "accessResult" in event ? event.accessResult : undefined;
}

function eventTime(event: AccessEvent | RealtimeAccessEvent) {
  return "eventTime" in event && event.eventTime ? event.eventTime : "receivedAt" in event ? event.receivedAt : undefined;
}

function eventTitle(event: AccessEvent | RealtimeAccessEvent) {
  if ("personName" in event && event.personName) return event.personName;
  if ("accessEventId" in event && event.accessEventId && !("id" in event)) return `Evento ${event.accessEventId.slice(0, 8)}`;
  if ("personType" in event && event.personType) return `${event.personType} ${event.personId?.slice(0, 8) ?? ""}`.trim();
  return "Evento recebido";
}

function eventDescription(event: AccessEvent | RealtimeAccessEvent) {
  if ("deviceName" in event || "areaName" in event || "personCpf" in event) {
    const richPieces = [
      "eventType" in event ? event.eventType : undefined,
      "deviceName" in event ? event.deviceName : undefined,
      "areaName" in event ? event.areaName : undefined,
      "personCpf" in event ? event.personCpf : undefined
    ].filter(Boolean);
    if (richPieces.length > 0) return richPieces.join(" · ");
  }

  const pieces = [
    "eventType" in event ? event.eventType : undefined,
    "deviceId" in event ? event.deviceId?.slice(0, 8) : undefined,
    "origin" in event ? event.origin : undefined
  ].filter(Boolean);
  return pieces.join(" · ") || "Sinal realtime recebido";
}

export function RealtimeFeed({
  title = "Ao vivo",
  events,
  compact = false
}: {
  title?: string;
  events: Array<AccessEvent | RealtimeAccessEvent>;
  compact?: boolean;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Activity className="h-5 w-5 text-sport-red" />
            <h2 className="text-base font-semibold text-slate-950">{title}</h2>
          </div>
          <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">{events.length} sinais</span>
        </div>
      </CardHeader>
      <CardContent className={compact ? "space-y-2 p-3" : "space-y-3"}>
        {events.length === 0 ? (
          <EmptyState label="Nenhum sinal realtime recebido." description="Ao simular ou receber eventos, eles entram no topo do feed." />
        ) : (
          events.map((event, index) => {
            const result = eventResult(event);
            const critical = result === "DENIED" || result === "ERROR";
            return (
              <div
                key={`${eventId(event) ?? "realtime"}-${index}`}
                className={`rounded-xl border p-3 ${critical ? "border-red-200 bg-red-50" : "border-slate-100 bg-slate-50"}`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <div className={`mt-0.5 flex h-9 w-9 items-center justify-center rounded-lg ${critical ? "bg-red-100 text-red-700" : "bg-white text-slate-600"}`}>
                      {critical ? <ShieldAlert className="h-4 w-4" /> : <Activity className="h-4 w-4" />}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-slate-950">{eventTitle(event)}</p>
                      <p className="mt-1 text-xs text-slate-500">{eventDescription(event)}</p>
                    </div>
                  </div>
                  {result ? <StatusBadge value={result} /> : null}
                </div>
                {eventTime(event) ? <p className="mt-2 text-xs font-medium text-slate-500">{new Date(eventTime(event) as string).toLocaleString("pt-BR")}</p> : null}
              </div>
            );
          })
        )}
      </CardContent>
    </Card>
  );
}
