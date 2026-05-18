import clsx from "clsx";
import { Badge } from "@/src/components/ui/Badge";
import { type RealtimeConnectionStatus } from "@/src/hooks/useRealtime";

export function RealtimeIndicator({ status }: { status: RealtimeConnectionStatus }) {
  const connected = status === "connected";
  const label = connected ? "Conectado ao realtime" : status === "connecting" ? "Conectando realtime" : status === "error" ? "Erro no realtime" : "Realtime offline";

  return (
    <Badge tone={connected ? "green" : status === "error" ? "red" : "amber"} className="gap-2">
      <span className={clsx("h-2 w-2 rounded-full", connected ? "animate-pulse bg-emerald-500" : "bg-current")} />
      {label}
    </Badge>
  );
}
