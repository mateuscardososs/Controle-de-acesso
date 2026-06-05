import { Activity, ShieldAlert } from "lucide-react";
import { motion } from "framer-motion";
import { displayAreaName } from "@/lib/areaLabels";
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
  if ("rawCardName" in event && event.rawCardName) return event.rawCardName;
  if ("externalUserId" in event && event.externalUserId) return `Usuário ${event.externalUserId}`;
  const rawCardName = "rawPayload" in event ? event.rawPayload?.CardName : undefined;
  if (typeof rawCardName === "string" && rawCardName) return rawCardName;
  return "Usuário não identificado";
}

function eventDescription(event: AccessEvent | RealtimeAccessEvent) {
  if ("deviceName" in event || "areaName" in event || "personCpf" in event) {
    const richPieces = [
      "eventType" in event ? event.eventType : undefined,
      "deviceName" in event ? event.deviceName : undefined,
      "areaName" in event && event.areaName ? displayAreaName(event.areaName) : undefined,
      "personCpf" in event ? formatCpf(event.personCpf) : undefined
    ].filter(Boolean);
    if (richPieces.length > 0) return richPieces.join(" · ");
  }

  const pieces = [
      "eventType" in event ? event.eventType : undefined,
      "deviceId" in event ? event.deviceId?.slice(0, 8) : undefined,
      "origin" in event ? originLabel(event.origin) : undefined
  ].filter(Boolean);
  return pieces.join(" · ") || "Sinal realtime recebido";
}

function formatCpf(value?: string | null) {
  if (!value) return undefined;
  const digits = value.replace(/\D/g, "");
  if (digits.length !== 11) return value;
  return `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6, 9)}-${digits.slice(9)}`;
}

function originLabel(origin?: string) {
  if (origin === "INTELBRAS_REAL") return "Intelbras Real";
  if (origin === "INTELBRAS_SIMULATOR") return "Simulador Intelbras";
  if (origin === "SIMULATION") return "Simulação";
  return origin;
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
            <Activity className="h-5 w-5 text-brand-wine" />
            <h2 className="text-base font-semibold text-slate-50">{title}</h2>
          </div>
          <span className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">{events.length} sinais</span>
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
              <motion.div
                key={`${eventId(event) ?? "realtime"}-${index}`}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.18, delay: Math.min(index * 0.015, 0.09) }}
                className={`rounded-2xl border p-3 ${critical ? "border-red-300/20 bg-red-500/12" : "border-white/10 bg-white/[0.045]"}`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <div className={`mt-0.5 flex h-9 w-9 items-center justify-center rounded-xl border ${critical ? "border-red-300/20 bg-red-500/12 text-red-200" : "border-white/10 bg-white/[0.06] text-slate-400"}`}>
                      {critical ? <ShieldAlert className="h-4 w-4" /> : <Activity className="h-4 w-4" />}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-slate-100">{eventTitle(event)}</p>
                      <p className="mt-1 text-xs text-slate-500">{eventDescription(event)}</p>
                    </div>
                  </div>
                  {result ? <StatusBadge value={result} /> : null}
                </div>
                {eventTime(event) ? <p className="mt-2 text-xs font-medium text-slate-500">{new Date(eventTime(event) as string).toLocaleString("pt-BR")}</p> : null}
              </motion.div>
            );
          })
        )}
      </CardContent>
    </Card>
  );
}
