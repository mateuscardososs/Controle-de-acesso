"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { displayAreaName } from "@/lib/areaLabels";
import { StatCard } from "@/components/StatCard";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Button } from "@/src/components/ui/Button";
import { Input } from "@/src/components/ui/Input";
import { Badge } from "@/src/components/ui/Badge";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { AccessEvent } from "@/services/accessEventService";
import { deviceService } from "@/services/deviceService";
import { dashboardService } from "@/services/dashboardService";
import { adminCleanupService } from "@/services/adminCleanupService";
import { authService } from "@/services/authService";
import { apiErrorMessage } from "@/lib/errors";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, CalendarClock, IdCard, MonitorCog, RadioTower, ShieldAlert, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useRealtime } from "@/src/hooks/useRealtime";
import { RealtimeIndicator } from "@/src/components/shared/RealtimeIndicator";
import { RealtimeFeed } from "@/src/components/shared/RealtimeFeed";
import { Area, AreaChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

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

function localDate() {
  const date = new Date();
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 10);
}

export default function DashboardPage() {
  const queryClient = useQueryClient();
  const realtime = useRealtime();
  const [peaksOpen, setPeaksOpen] = useState(false);
  const [peaksDate, setPeaksDate] = useState(() => localDate());
  const [cleanupOpen, setCleanupOpen] = useState(false);
  const [cleanupConfirmation, setCleanupConfirmation] = useState("");
  const [cleanupMessage, setCleanupMessage] = useState("");
  const user = useQuery({ queryKey: ["me"], queryFn: authService.me, retry: false });
  const summary = useQuery({ queryKey: ["dashboard-summary"], queryFn: dashboardService.summary });
  const trafficPeaks = useQuery({
    queryKey: ["traffic-peaks", peaksDate],
    queryFn: () => dashboardService.trafficPeaks(peaksDate),
    enabled: peaksOpen
  });
  const events = useQuery({
    queryKey: ["dashboard-recent-events"],
    queryFn: () => dashboardService.recentEvents(6),
    refetchInterval: realtime.connected ? false : 10000
  });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const recentEvents = (events.data ?? []).slice(0, 6);
  const offlineDevices = (devices.data ?? []).filter((device) => device.status !== "ONLINE");
  const canCleanup = user.data?.role === "ADMIN";
  const cleanupAccessEvents = useMutation({
    mutationFn: () => adminCleanupService.accessEvents(cleanupConfirmation),
    onSuccess: (response) => {
      setCleanupOpen(false);
      setCleanupConfirmation("");
      setCleanupMessage(response.message);
      queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-recent-events"] });
      queryClient.invalidateQueries({ queryKey: ["access-events"] });
      queryClient.invalidateQueries({ queryKey: ["traffic-peaks"] });
    },
    onError: (error) => setCleanupMessage(apiErrorMessage(error, "Não foi possível limpar os eventos."))
  });

  useEffect(() => {
    if (realtime.accessEvents.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["dashboard-recent-events"] });
    queryClient.invalidateQueries({ queryKey: ["access-events"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
  }, [queryClient, realtime.accessEvents.length]);

  useEffect(() => {
    if (realtime.deviceStatuses.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["devices"] });
  }, [queryClient, realtime.deviceStatuses.length]);

  const peaksData = trafficPeaks.data ?? [];
  const peakEntryItem = peaksData.length > 0 ? peaksData.reduce((max, item) => item.entries > max.entries ? item : max) : null;
  const totalAllowed = peaksData.reduce((sum, item) => sum + item.allowed, 0);
  const totalDenied = peaksData.reduce((sum, item) => sum + item.denied, 0);
  const busyHourItem = peaksData.length > 0 ? peaksData.reduce((max, item) => (item.entries + item.exits + item.passages) > (max.entries + max.exits + max.passages) ? item : max) : null;
  const chartData = peaksData.map((item) => ({ ...item, label: `${String(item.hour).padStart(2, "0")}h` }));

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Operacao"
        title="Dashboard"
        description="Indicadores, eventos recentes e saude dos dispositivos conectados aos endpoints reais da plataforma."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {canCleanup ? (
              <Button variant="danger" icon={Trash2} onClick={() => { setCleanupOpen(true); setCleanupMessage(""); }}>
                Limpar lista
              </Button>
            ) : null}
            <RealtimeIndicator status={realtime.status} message={realtime.statusMessage} />
          </div>
        }
      />
      {summary.isLoading ? <LoadingState label="Carregando indicadores..." /> : null}
      {summary.isError ? <ErrorState label="Não foi possível carregar o dashboard." /> : null}
      {cleanupMessage ? <div className="mb-4 rounded-xl border border-white/10 bg-white/[0.055] px-4 py-3 text-sm font-medium text-slate-200">{cleanupMessage}</div> : null}
      {!summary.isLoading && !summary.isError ? (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            {[
              { title: "Colaboradores ativos", value: summary.data?.totalEmployees ?? 0, icon: IdCard, description: "Base cadastrada" },
              { title: "Dispositivos", value: summary.data?.totalDevices ?? 0, icon: MonitorCog, description: "Catracas e controladoras" },
              { title: "Eventos hoje", value: summary.data?.todayEvents ?? 0, icon: CalendarClock, description: "Movimento do dia", onClick: () => setPeaksOpen(true) },
              { title: "Acessos negados", value: summary.data?.deniedAccesses ?? 0, icon: ShieldAlert, description: "Requer acompanhamento" }
            ].map((item) => (
              <div key={item.title} className="relative">
                {"onClick" in item ? (
                  <button type="button" className="block w-full text-left" onClick={item.onClick}>
                    <StatCard title={item.title} value={item.value} icon={item.icon} description={item.description} />
                  </button>
                ) : (
                  <StatCard title={item.title} value={item.value} icon={item.icon} description={item.description} />
                )}
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
                    { key: "person", header: "Pessoa", className: "min-w-[170px]", render: personLabel },
                    { key: "cpf", header: "CPF", className: "whitespace-nowrap font-mono text-xs", render: (event) => formatCpf(event.personCpf) },
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
                        <p className="text-xs text-slate-500">{displayAreaName(device.areaName)} · {device.ipAddress}</p>
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

      <Modal title="Picos de horário" description="Movimento por hora no dia selecionado" open={peaksOpen} onClose={() => setPeaksOpen(false)}>
        <div className="mb-5 flex items-center gap-3">
          <label className="text-sm font-medium text-slate-400">Data</label>
          <input
            type="date"
            value={peaksDate}
            max={localDate()}
            onChange={(e) => setPeaksDate(e.target.value)}
            className="rounded-lg border border-white/15 bg-white/[0.055] px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-red-700"
          />
        </div>

        {trafficPeaks.isLoading ? <LoadingState label="Carregando picos de horário..." /> : null}
        {trafficPeaks.isError ? <ErrorState label="Não foi possível carregar os picos de horário." /> : null}

        {!trafficPeaks.isLoading && !trafficPeaks.isError && chartData.length === 0 ? (
          <EmptyState label="Nenhum evento registrado nesta data." description="Selecione outra data ou aguarde novos eventos." />
        ) : null}

        {chartData.length > 0 ? (
          <>
            <div className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-xl border border-white/10 bg-white/[0.045] p-3">
                <p className="text-xs text-slate-400">Pico de entrada</p>
                <p className="mt-1 text-xl font-bold text-sky-400">{peakEntryItem ? `${String(peakEntryItem.hour).padStart(2, "0")}h` : "—"}</p>
                <p className="text-xs text-slate-500">{peakEntryItem ? `${peakEntryItem.entries} entradas` : "sem dados"}</p>
              </div>
              <div className="rounded-xl border border-white/10 bg-white/[0.045] p-3">
                <p className="text-xs text-slate-400">Total liberados</p>
                <p className="mt-1 text-xl font-bold text-emerald-400">{totalAllowed}</p>
                <p className="text-xs text-slate-500">acessos permitidos</p>
              </div>
              <div className="rounded-xl border border-white/10 bg-white/[0.045] p-3">
                <p className="text-xs text-slate-400">Total negados</p>
                <p className="mt-1 text-xl font-bold text-red-400">{totalDenied}</p>
                <p className="text-xs text-slate-500">acessos negados</p>
              </div>
              <div className="rounded-xl border border-white/10 bg-white/[0.045] p-3">
                <p className="text-xs text-slate-400">Maior fluxo</p>
                <p className="mt-1 text-xl font-bold text-slate-50">{busyHourItem ? `${String(busyHourItem.hour).padStart(2, "0")}h` : "—"}</p>
                <p className="text-xs text-slate-500">{busyHourItem ? `${busyHourItem.entries + busyHourItem.exits + busyHourItem.passages} eventos` : "sem dados"}</p>
              </div>
            </div>

            <div className="h-[300px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData} margin={{ top: 8, right: 18, left: -6, bottom: 0 }}>
                  <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} strokeDasharray="3 3" />
                  <XAxis dataKey="label" stroke="#64748b" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} />
                  <YAxis stroke="#64748b" tickLine={false} axisLine={false} allowDecimals={false} tick={{ fontSize: 11 }} />
                  <Tooltip
                    cursor={{ stroke: "rgba(148,163,184,0.35)", strokeWidth: 1 }}
                    contentStyle={{ background: "#0f172a", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 10, color: "#e2e8f0", fontSize: 12 }}
                    labelStyle={{ color: "#94a3b8", marginBottom: 6, fontWeight: 600 }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                  {busyHourItem ? (
                    <ReferenceLine x={`${String(busyHourItem.hour).padStart(2, "0")}h`} stroke="rgba(245,158,11,0.75)" strokeDasharray="4 4" label={{ value: "pico", fill: "#fbbf24", fontSize: 11, position: "insideTopRight" }} />
                  ) : null}
                  <Line type="monotone" dataKey="entries" name="Entradas" stroke="#38bdf8" strokeWidth={3} dot={false} activeDot={{ r: 5, strokeWidth: 2 }} />
                  <Line type="monotone" dataKey="allowed" name="Liberados" stroke="#22c55e" strokeWidth={3} dot={false} activeDot={{ r: 5, strokeWidth: 2 }} />
                  <Line type="monotone" dataKey="denied" name="Negados" stroke="#ef4444" strokeWidth={3} dot={false} activeDot={{ r: 5, strokeWidth: 2 }} />
                  <Line type="monotone" dataKey="passages" name="Passagens/saídas" stroke="#f59e0b" strokeWidth={3} dot={false} activeDot={{ r: 5, strokeWidth: 2 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </>
        ) : null}
      </Modal>

      <Modal
        open={cleanupOpen}
        title="Limpar eventos do dashboard"
        description="Esta ação limpa eventos/logs operacionais de acesso. Visitantes, colaboradores, dispositivos, áreas, configurações e backups não serão apagados."
        onClose={() => {
          if (!cleanupAccessEvents.isPending) {
            setCleanupOpen(false);
            setCleanupConfirmation("");
          }
        }}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            cleanupAccessEvents.mutate();
          }}
          className="space-y-4"
        >
          <div className="rounded-xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm text-amber-100">
            Digite <span className="font-semibold">LIMPAR_EVENTOS</span> para confirmar a limpeza dos eventos exibidos no dashboard.
          </div>
          <Input label="Confirmação" value={cleanupConfirmation} onChange={(event) => setCleanupConfirmation(event.target.value)} />
          {cleanupAccessEvents.isError ? <ErrorState label={cleanupMessage || "Não foi possível limpar os eventos."} /> : null}
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" disabled={cleanupAccessEvents.isPending} onClick={() => setCleanupOpen(false)}>Cancelar</Button>
            <Button type="submit" variant="danger" icon={Trash2} loading={cleanupAccessEvents.isPending} disabled={cleanupConfirmation !== "LIMPAR_EVENTOS"}>
              Limpar eventos
            </Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}

function originLabel(origin?: string) {
  if (origin === "INTELBRAS_REAL") return "Intelbras Real";
  if (origin === "INTELBRAS_SIMULATOR") return "Simulador Intelbras";
  if (origin === "SIMULATION") return "Simulação";
  return origin ?? "Não informado";
}

function personLabel(event: AccessEvent) {
  if (event.personName) return event.personName;
  if (event.rawCardName) return event.rawCardName;
  if (event.externalUserId) return `Usuário ${event.externalUserId}`;
  return "Usuário não identificado";
}

function formatCpf(value?: string | null) {
  if (!value) return "Não informado";
  const digits = value.replace(/\D/g, "");
  if (digits.length !== 11) return value;
  return `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6, 9)}-${digits.slice(9)}`;
}
