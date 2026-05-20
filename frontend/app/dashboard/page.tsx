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
import { Area, AreaChart, ResponsiveContainer, Tooltip } from "recharts";

function metricTrend(value: number) {
  const safeValue = Math.max(value, 1);
  return [0.38, 0.52, 0.48, 0.68, 0.61, 0.82, 1].map((factor, index) => ({
    name: index,
    value: Math.max(1, Math.round(safeValue * factor))
  }));
}

function Sparkline({ value }: { value: number }) {
  return (
    <div className="h-12 w-24">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={metricTrend(value)}>
          <defs>
            <linearGradient id={`spark-${value}`} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#B43A4B" stopOpacity={0.52} />
              <stop offset="100%" stopColor="#B43A4B" stopOpacity={0} />
            </linearGradient>
          </defs>
          <Tooltip contentStyle={{ display: "none" }} cursor={false} />
          <Area dataKey="value" stroke="#B43A4B" strokeWidth={2} fill={`url(#spark-${value})`} dot={false} isAnimationActive />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

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
            {[
              { title: "Colaboradores ativos", value: summary.data?.totalEmployees ?? 0, icon: IdCard, description: "Base cadastrada" },
              { title: "Dispositivos", value: summary.data?.totalDevices ?? 0, icon: MonitorCog, description: "Catracas e controladoras" },
              { title: "Eventos hoje", value: summary.data?.todayEvents ?? 0, icon: CalendarClock, description: "Movimento do dia" },
              { title: "Acessos negados", value: summary.data?.deniedAccesses ?? 0, icon: ShieldAlert, description: "Requer acompanhamento" }
            ].map((item) => (
              <div key={item.title} className="relative">
                <StatCard title={item.title} value={item.value} icon={item.icon} description={item.description} />
                <div className="pointer-events-none absolute bottom-5 right-5">
                  <Sparkline value={Number(item.value)} />
                </div>
              </div>
            ))}
          </div>

          <div className="grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
            <section>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-base font-semibold text-slate-50">Eventos recentes</h2>
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
                    { key: "origin", header: "Origem", render: (event) => <StatusBadge value={originLabel(event.origin)} /> }
                  ]}
                />
              ) : null}
            </section>

            <div className="space-y-6">
              <RealtimeFeed title="Ao vivo" events={realtime.accessEvents} compact />
              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-base font-semibold text-slate-50">Status dos dispositivos</h2>
                    <RadioTower className="h-5 w-5 text-brand-wine" />
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  {(devices.data ?? []).slice(0, 5).map((device) => (
                    <div key={device.id} className="flex items-center justify-between gap-3 rounded-2xl border border-white/10 bg-white/[0.045] px-3 py-2">
                      <div>
                        <p className="text-sm font-semibold text-slate-100">{device.name}</p>
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
                    <h2 className="text-base font-semibold text-slate-50">Alertas operacionais</h2>
                    <Activity className="h-5 w-5 text-brand-wine" />
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  {offlineDevices.length > 0 ? (
                    <div className="rounded-2xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm font-medium text-amber-100">
                      {offlineDevices.length} dispositivo(s) offline ou sem status confirmado.
                    </div>
                  ) : (
                    <div className="rounded-2xl border border-emerald-300/20 bg-emerald-400/12 p-3 text-sm font-medium text-emerald-100">
                      Nenhum alerta critico identificado nos dados atuais.
                    </div>
                  )}
                  {(summary.data?.deniedAccesses ?? 0) > 0 ? (
                    <div className="rounded-2xl border border-red-300/20 bg-red-500/12 p-3 text-sm font-medium text-red-100">
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

function originLabel(origin?: string) {
  if (origin === "INTELBRAS_REAL") return "Intelbras Real";
  if (origin === "INTELBRAS_SIMULATOR") return "Simulador Intelbras";
  if (origin === "SIMULATION") return "Simulação";
  return origin ?? "Não informado";
}
