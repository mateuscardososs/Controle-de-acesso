"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { accessEventService } from "@/services/accessEventService";
import { areaService } from "@/services/areaService";
import { deviceService } from "@/services/deviceService";
import { employeeService } from "@/services/employeeService";
import { useQuery } from "@tanstack/react-query";
import { Activity, ArrowDownToLine, ArrowUpFromLine, ShieldAlert, ShieldCheck, TriangleAlert } from "lucide-react";
import { useMemo, useState } from "react";

const filters = [
  { key: "ALL", label: "Todos", icon: Activity },
  { key: "ALLOWED", label: "Permitidos", icon: ShieldCheck },
  { key: "DENIED", label: "Negados", icon: ShieldAlert },
  { key: "ERROR", label: "Erro", icon: TriangleAlert },
  { key: "ENTRY", label: "Entrada", icon: ArrowDownToLine },
  { key: "EXIT", label: "Saida", icon: ArrowUpFromLine }
];

export default function AccessEventsPage() {
  const events = useQuery({ queryKey: ["access-events"], queryFn: accessEventService.list });
  const employees = useQuery({ queryKey: ["employees"], queryFn: employeeService.list });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [filter, setFilter] = useState("ALL");

  const employeeById = useMemo(() => new Map((employees.data ?? []).map((employee) => [employee.id, employee.fullName])), [employees.data]);
  const deviceById = useMemo(() => new Map((devices.data ?? []).map((device) => [device.id, device.name])), [devices.data]);
  const areaById = useMemo(() => new Map((areas.data ?? []).map((area) => [area.id, area.name])), [areas.data]);

  const filteredEvents = (events.data ?? []).filter((event) => {
    if (filter === "ALL") return true;
    if (filter === "ERROR") return event.accessResult === "ERROR";
    return event.accessResult === filter || event.eventType === filter;
  });

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Monitoramento"
        title="Eventos de acesso"
        description="Historico operacional de entradas, saidas e resultados registrados pela API."
      />
      <Card className="mb-5">
        <CardContent className="flex flex-wrap gap-2">
          {filters.map((item) => (
            <Button
              key={item.key}
              icon={item.icon}
              variant={filter === item.key ? "primary" : "secondary"}
              onClick={() => setFilter(item.key)}
            >
              {item.label}
            </Button>
          ))}
        </CardContent>
      </Card>
      {events.isLoading ? <LoadingState label="Carregando eventos..." /> : null}
      {events.isError ? <ErrorState label="Não foi possível carregar os eventos." /> : null}
      {!events.isLoading && !events.isError && events.data?.length === 0 ? (
        <EmptyState label="Nenhum evento registrado ainda." description="Os eventos aparecerao aqui assim que forem recebidos pelo backend." />
      ) : null}
      {!events.isLoading && !events.isError && events.data && events.data.length > 0 && filteredEvents.length === 0 ? (
        <EmptyState label="Nenhum evento para este filtro." description="Selecione outro filtro para ampliar a consulta." />
      ) : null}
      {!events.isLoading && !events.isError && filteredEvents.length > 0 ? (
        <div className="grid gap-6 xl:grid-cols-[1fr_21rem]">
          <DataTable
            data={filteredEvents}
            getRowKey={(event) => event.id}
            columns={[
              { key: "person", header: "Pessoa", render: (event) => personLabel(event, employeeById) },
              { key: "type", header: "Tipo", render: (event) => <StatusBadge value={event.eventType} /> },
              { key: "device", header: "Dispositivo", render: (event) => deviceById.get(event.deviceId) ?? event.deviceId.slice(0, 8) },
              { key: "area", header: "Area", render: (event) => areaById.get(event.areaId) ?? event.areaId.slice(0, 8) },
              { key: "result", header: "Resultado", render: (event) => <StatusBadge value={event.accessResult} /> },
              { key: "time", header: "Horario", render: (event) => new Date(event.eventTime).toLocaleString("pt-BR") }
            ]}
          />
          <Card>
            <CardHeader>
              <h2 className="text-base font-semibold text-slate-50">Feed recente</h2>
            </CardHeader>
            <CardContent className="space-y-3">
              {filteredEvents.slice(0, 6).map((event) => (
                <div key={event.id} className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <StatusBadge value={event.accessResult} />
                    <span className="text-xs font-medium text-slate-500">{new Date(event.eventTime).toLocaleTimeString("pt-BR")}</span>
                  </div>
                  <p className="text-sm font-semibold text-slate-100">{personLabel(event, employeeById)}</p>
                  <p className="mt-1 text-xs text-slate-500">{deviceById.get(event.deviceId) ?? "Dispositivo"} · {areaById.get(event.areaId) ?? "Area"}</p>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      ) : null}
    </AdminShell>
  );
}

function personLabel(event: { personId?: string | null; personType?: string; personName?: string | null; rawCardName?: string | null; externalUserId?: string | null; rawPayload?: Record<string, unknown> }, employeeById: Map<string, string>) {
  if (event.personName) return event.personName;
  if (event.rawCardName) return event.rawCardName;
  const cardName = event.rawPayload?.CardName;
  if (typeof cardName === "string" && cardName) return cardName;
  if (event.personId && employeeById.has(event.personId)) return employeeById.get(event.personId);
  if (event.externalUserId) return `Usuário ${event.externalUserId}`;
  return "Usuário não identificado";
}
