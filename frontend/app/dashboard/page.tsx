"use client";

import { AdminShell } from "@/components/AdminShell";
import { ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { StatCard } from "@/components/StatCard";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Badge } from "@/src/components/ui/Badge";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { accessEventService } from "@/services/accessEventService";
import { deviceService } from "@/services/deviceService";
import { dashboardService } from "@/services/dashboardService";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, CalendarClock, IdCard, MonitorCog, RadioTower, ShieldAlert } from "lucide-react";
import { useEffect } from "react";
import { useRealtime } from "@/src/hooks/useRealtime";
import { RealtimeIndicator } from "@/src/components/shared/RealtimeIndicator";
import { RealtimeFeed } from "@/src/components/shared/RealtimeFeed";

export default function DashboardPage() {
  const queryClient = useQueryClient();
  const realtime = useRealtime();
  const summary = useQuery({ queryKey: ["dashboard-summary"], queryFn: dashboardService.summary });
  const events = useQuery({ queryKey: ["access-events"], queryFn: accessEventService.list });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const recentEvents = (events.data ?? []).slice(0, 6);
  const offlineDevices = (devices.data ?? []).filter((device) => device.status !== "ONLINE");

  useEffect(() => {
    if (realtime.accessEvents.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["access-events"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
  }, [queryClient, realtime.accessEvents.length]);

  useEffect(() => {
    if (realtime.deviceStatuses.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["devices"] });
  }, [queryClient, realtime.deviceStatuses.length]);

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Operacao"
        title="Dashboard"
        description="Indicadores, eventos recentes e saude dos dispositivos conectados aos endpoints reais da plataforma."
        actions={<RealtimeIndicator status={realtime.status} />}
      />
      {summary.isLoading ? <LoadingState label="Carregando indicadores..." /> : null}
      {summary.isError ? <ErrorState label="Não foi possível carregar o dashboard." /> : null}
      {!summary.isLoading && !summary.isError ? (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <StatCard title="Colaboradores ativos" value={summary.data?.totalEmployees ?? 0} icon={IdCard} description="Base cadastrada para regras de acesso" />
            <StatCard title="Dispositivos cadastrados" value={summary.data?.totalDevices ?? 0} icon={MonitorCog} description="Catracas e controladoras integradas" />
            <StatCard title="Eventos hoje" value={summary.data?.todayEvents ?? 0} icon={CalendarClock} description="Fluxo operacional do dia" />
            <StatCard title="Acessos negados" value={summary.data?.deniedAccesses ?? 0} icon={ShieldAlert} description="Ocorrencias que pedem acompanhamento" />
          </div>

          <div className="grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
            <section>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-base font-semibold text-slate-950">Eventos recentes</h2>
                <Badge tone="slate">{recentEvents.length} registros</Badge>
              </div>
              {events.isLoading ? <LoadingState label="Carregando eventos recentes..." /> : null}
              {events.isError ? <ErrorState label="Não foi possível carregar eventos recentes." /> : null}
              {!events.isLoading && !events.isError ? (
                <DataTable
                  data={recentEvents}
                  getRowKey={(event) => event.id}
                  columns={[
                    { key: "time", header: "Horario", render: (event) => new Date(event.eventTime).toLocaleString("pt-BR") },
                    { key: "type", header: "Tipo", render: (event) => <StatusBadge value={event.eventType} /> },
                    { key: "result", header: "Resultado", render: (event) => <StatusBadge value={event.accessResult} /> },
                    { key: "origin", header: "Origem", render: (event) => <StatusBadge value={event.origin} /> }
                  ]}
                />
              ) : null}
            </section>

            <div className="space-y-6">
              <RealtimeFeed title="Ao vivo" events={realtime.accessEvents} compact />
              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-base font-semibold text-slate-950">Status dos dispositivos</h2>
                    <RadioTower className="h-5 w-5 text-sport-red" />
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  {(devices.data ?? []).slice(0, 5).map((device) => (
                    <div key={device.id} className="flex items-center justify-between gap-3 rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
                      <div>
                        <p className="text-sm font-semibold text-slate-900">{device.name}</p>
                        <p className="text-xs text-slate-500">{device.areaName} · {device.ipAddress}</p>
                      </div>
                      <StatusBadge value={device.status} />
                    </div>
                  ))}
                  {!devices.isLoading && (devices.data ?? []).length === 0 ? <p className="text-sm text-slate-500">Nenhum dispositivo cadastrado.</p> : null}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-base font-semibold text-slate-950">Alertas operacionais</h2>
                    <Activity className="h-5 w-5 text-sport-red" />
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  {offlineDevices.length > 0 ? (
                    <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm font-medium text-amber-900">
                      {offlineDevices.length} dispositivo(s) offline ou sem status confirmado.
                    </div>
                  ) : (
                    <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-medium text-emerald-800">
                      Nenhum alerta critico identificado nos dados atuais.
                    </div>
                  )}
                  {(summary.data?.deniedAccesses ?? 0) > 0 ? (
                    <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm font-medium text-red-800">
                      Existem acessos negados acumulados para revisao da seguranca.
                    </div>
                  ) : null}
                </CardContent>
              </Card>
            </div>
          </div>
        </div>
      ) : null}
    </AdminShell>
  );
}
