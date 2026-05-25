"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, AlertTriangle, DownloadCloud, MonitorCog, RadioTower, RotateCcw, ShieldAlert, Tv } from "lucide-react";
import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { authService } from "@/services/authService";
import { accessEventService } from "@/services/accessEventService";
import { deviceService } from "@/services/deviceService";
import { dashboardService } from "@/services/dashboardService";
import { guestService } from "@/services/guestService";
import { integrationService } from "@/services/integrationService";
import { useRealtime } from "@/src/hooks/useRealtime";
import { AccessEventSimulator } from "@/src/components/shared/AccessEventSimulator";
import { RealtimeFeed } from "@/src/components/shared/RealtimeFeed";
import { RealtimeIndicator } from "@/src/components/shared/RealtimeIndicator";
import { StatCard } from "@/components/StatCard";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Button } from "@/src/components/ui/Button";
import { Badge } from "@/src/components/ui/Badge";
import { StatusBadge } from "@/src/components/shared/StatusBadge";

export default function OperationsPage() {
  const queryClient = useQueryClient();
  const realtime = useRealtime();
  const [importMessage, setImportMessage] = useState("");
  const user = useQuery({ queryKey: ["me"], queryFn: authService.me, retry: false });
  const summary = useQuery({ queryKey: ["dashboard-summary"], queryFn: dashboardService.summary });
  const events = useQuery({ queryKey: ["access-events"], queryFn: accessEventService.list });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const guests = useQuery({ queryKey: ["guests", "today"], queryFn: guestService.today });

  const simulatorEnabled = process.env.NEXT_PUBLIC_SIMULATOR_ENABLED === "true"
    || (process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_SIMULATOR_ENABLED !== "false");
  const canSimulate = simulatorEnabled && (user.data?.role === "ADMIN" || user.data?.role === "HR");
  const canImportIntelbrasEvents = user.data?.role === "ADMIN";
  const importEvents = useMutation({
    mutationFn: integrationService.importIntelbrasEventsNow,
    onSuccess: (result) => {
      setImportMessage(`${result.imported} evento(s) importado(s), ${result.skipped} duplicado(s), ${result.received} recebido(s).`);
      queryClient.invalidateQueries({ queryKey: ["access-events"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
    },
    onError: () => setImportMessage("Não foi possível importar eventos da Intelbras agora.")
  });
  const liveEvents = useMemo(() => {
    const realtimeIds = new Set(realtime.accessEvents.map((event) => event.accessEventId ?? event.id).filter(Boolean));
    const recentRestEvents = (events.data ?? []).filter((event) => !realtimeIds.has(event.id)).slice(0, 10);
    return [...realtime.accessEvents, ...recentRestEvents].slice(0, 18);
  }, [events.data, realtime.accessEvents]);

  const devicesWithRealtime = useMemo(() => {
    const statusByDevice = new Map(realtime.deviceStatuses.map((status) => [status.deviceId, status.status]));
    return (devices.data ?? []).map((device) => ({
      ...device,
      status: statusByDevice.get(device.id) ?? device.status
    }));
  }, [devices.data, realtime.deviceStatuses]);

  const offlineDevices = devicesWithRealtime.filter((device) => device.status !== "ONLINE");
  const deniedEvents = (events.data ?? []).filter((event) => event.accessResult === "DENIED" || event.accessResult === "ERROR").slice(0, 5);
  const todayGuests = guests.data ?? [];
  const pendingGuests = todayGuests.filter((guest) => guest.status === "PENDING_REGISTRATION" || guest.status === "INVITED");
  const expiredGuests = todayGuests.filter((guest) => guest.status === "EXPIRED");
  const guestsWithoutPhoto = todayGuests.filter((guest) => !guest.facePhotoUrl);
  const pendingSync = todayGuests.filter((guest) => guest.syncStatus === "PENDING_SYNC" || guest.syncStatus === "SYNCING");
  const failedSync = todayGuests.filter((guest) => guest.syncStatus === "SYNC_FAILED");
  const latestSyncs = todayGuests
    .filter((guest) => guest.lastSyncAt)
    .sort((a, b) => new Date(b.lastSyncAt ?? 0).getTime() - new Date(a.lastSyncAt ?? 0).getTime())
    .slice(0, 4);
  const intelbrasTarget = devicesWithRealtime.find((device) => [device.model, device.name].some((value) => value?.toLowerCase().includes("intelbras")))?.model ?? "Intelbras";
  const integrationSignals = realtime.integrationSync.map((event) => ({
    ...event,
    title: integrationSyncTitle(event, todayGuests, intelbrasTarget)
  }));

  useEffect(() => {
    if (realtime.accessEvents.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["access-events"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
  }, [queryClient, realtime.accessEvents.length]);

  useEffect(() => {
    if (realtime.deviceStatuses.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["devices"] });
  }, [queryClient, realtime.deviceStatuses.length]);

  useEffect(() => {
    if (realtime.integrationSync.length === 0) return;
    queryClient.invalidateQueries({ queryKey: ["guests", "today"] });
  }, [queryClient, realtime.integrationSync.length]);

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Central de monitoramento"
        title="Operação ao vivo"
        description="Sala de controle para acompanhar eventos, saude dos dispositivos e alertas antes da integracao Intelbras real."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="secondary"
              icon={DownloadCloud}
              loading={importEvents.isPending}
              disabled={!canImportIntelbrasEvents || importEvents.isPending}
              title={canImportIntelbrasEvents ? "Buscar eventos reais AccessControlCardRec na Intelbras" : "Apenas administradores podem importar eventos"}
              onClick={() => importEvents.mutate()}
            >
              Importar eventos agora
            </Button>
            <RealtimeIndicator status={realtime.status} message={realtime.statusMessage} />
            <Badge tone="slate" className="gap-2"><Tv className="h-3.5 w-3.5" /> TV mode</Badge>
          </div>
        }
      />

      {importMessage ? (
        <div className={`mb-4 rounded-xl border px-4 py-3 text-sm font-semibold ${importEvents.isError ? "border-red-300/20 bg-red-500/12 text-red-100" : "border-emerald-300/20 bg-emerald-400/12 text-emerald-100"}`}>
          {importMessage}
        </div>
      ) : null}

      <div className="mb-6 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard title="Eventos hoje" value={summary.data?.todayEvents ?? 0} icon={Activity} description="Atualizado por consulta e sinais realtime" />
        <StatCard title="Dispositivos online" value={devicesWithRealtime.filter((device) => device.status === "ONLINE").length} icon={RadioTower} description="Status atual conhecido" />
        <StatCard title="Dispositivos atenção" value={offlineDevices.length} icon={MonitorCog} description="Offline ou desconhecidos" />
        <StatCard title="Visitantes hoje" value={todayGuests.length} icon={ShieldAlert} description="Convidados no periodo operacional" />
        <StatCard title="Sync Intelbras" value={failedSync.length} icon={RotateCcw} description="Falhas de sincronizacao" />
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.25fr_0.75fr]">
        <div className="space-y-6">
          {events.isLoading ? <LoadingState label="Carregando base de eventos..." /> : null}
          {events.isError ? <ErrorState label="Não foi possível carregar eventos." /> : null}
          {!events.isLoading && !events.isError ? <RealtimeFeed title="Feed ao vivo" events={liveEvents} /> : null}

          <Card>
            <CardHeader>
              <h2 className="text-base font-semibold text-slate-50">Sincronização Intelbras</h2>
            </CardHeader>
            <CardContent className="grid gap-3 md:grid-cols-3">
              <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Pendentes</p>
                <p className="mt-2 text-2xl font-semibold text-slate-50">{pendingSync.length}</p>
              </div>
              <div className="rounded-2xl border border-red-300/20 bg-red-500/12 p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-red-200">Falhadas</p>
                <p className="mt-2 text-2xl font-semibold text-red-100">{failedSync.length}</p>
              </div>
              <div className="rounded-2xl border border-emerald-300/20 bg-emerald-400/12 p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-emerald-200">Últimas syncs</p>
                <p className="mt-2 text-2xl font-semibold text-emerald-100">{latestSyncs.length}</p>
              </div>
              {integrationSignals.slice(0, 4).map((event, index) => (
                <div key={`${event.personId}-${index}`} className="rounded-2xl border border-white/10 bg-white/[0.045] p-3 md:col-span-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-semibold text-slate-100">{event.title}</p>
                    <StatusBadge value={event.syncStatus} />
                  </div>
                  {event.message ? <p className="mt-1 text-xs text-slate-500">{event.message}</p> : null}
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <AlertTriangle className="h-5 w-5 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Alertas operacionais</h2>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              {realtime.systemAlerts.map((alert, index) => (
                <div key={`${alert.id ?? alert.message ?? "alert"}-${index}`} className="rounded-2xl border border-amber-300/20 bg-amber-400/12 p-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-semibold text-amber-100">{alert.title ?? alert.message ?? "Alerta recebido"}</p>
                    <StatusBadge value={alert.severity ?? alert.level ?? "WARNING"} />
                  </div>
                  {alert.message && alert.title ? <p className="mt-1 text-xs text-amber-200/80">{alert.message}</p> : null}
                </div>
              ))}
              {offlineDevices.length > 0 ? (
                <div className="rounded-2xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm font-medium text-amber-100">
                  {offlineDevices.length} dispositivo(s) offline ou sem comunicacao confirmada.
                </div>
              ) : null}
              {deniedEvents.map((event) => (
                <div key={event.id} className="rounded-2xl border border-red-300/20 bg-red-500/12 p-3 text-sm font-medium text-red-100">
                  Evento {event.accessResult.toLowerCase()} em {new Date(event.eventTime).toLocaleString("pt-BR")}.
                </div>
              ))}
              {pendingGuests.length > 0 ? (
                <div className="rounded-2xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm font-medium text-amber-100">
                  {pendingGuests.length} visitante(s) ainda com cadastro pendente.
                </div>
              ) : null}
              {guestsWithoutPhoto.length > 0 ? (
                <div className="rounded-2xl border border-red-300/20 bg-red-500/12 p-3 text-sm font-medium text-red-100">
                  {guestsWithoutPhoto.length} visitante(s) sem foto facial.
                </div>
              ) : null}
              {expiredGuests.length > 0 ? (
                <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3 text-sm font-medium text-slate-300">
                  {expiredGuests.length} convite(s) expirado(s) no periodo.
                </div>
              ) : null}
              {realtime.systemAlerts.length === 0 && offlineDevices.length === 0 && deniedEvents.length === 0 && pendingGuests.length === 0 && guestsWithoutPhoto.length === 0 ? (
                <EmptyState label="Nenhum alerta operacional ativo." description="Falhas, negados e alertas do sistema aparecerao aqui." />
              ) : null}
            </CardContent>
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <h2 className="text-base font-semibold text-slate-50">Painel de dispositivos</h2>
            </CardHeader>
            <CardContent className="space-y-3">
              {devices.isLoading ? <LoadingState label="Carregando dispositivos..." /> : null}
              {devices.isError ? <ErrorState label="Não foi possível carregar dispositivos." /> : null}
              {!devices.isLoading && !devices.isError && devicesWithRealtime.length === 0 ? <EmptyState label="Nenhum dispositivo cadastrado." /> : null}
              {devicesWithRealtime.map((device) => (
                <div key={device.id} className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-100">{device.name}</p>
                      <p className="mt-1 text-xs text-slate-500">{device.areaName} · {device.ipAddress}</p>
                    </div>
                    <StatusBadge value={device.status} />
                  </div>
                  <p className="mt-2 text-xs text-slate-500">Ultimo heartbeat: {device.lastHeartbeatAt ? new Date(device.lastHeartbeatAt).toLocaleString("pt-BR") : "nao informado"}</p>
                </div>
              ))}
            </CardContent>
          </Card>

          {simulatorEnabled && canSimulate ? (
            <AccessEventSimulator
              onSuccess={() => {
                queryClient.invalidateQueries({ queryKey: ["access-events"] });
                queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] });
              }}
            />
          ) : simulatorEnabled ? (
            <Card>
              <CardContent>
                <p className="text-sm font-semibold text-slate-100">Simulador indisponivel</p>
                <p className="mt-1 text-sm text-slate-500">Seu perfil possui acesso somente leitura para a operacao ao vivo.</p>
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <h2 className="text-base font-semibold text-slate-50">Visitantes do dia</h2>
            </CardHeader>
            <CardContent className="space-y-3">
              {guests.isLoading ? <LoadingState label="Carregando visitantes..." /> : null}
              {guests.isError ? <ErrorState label="Não foi possível carregar visitantes." /> : null}
              {!guests.isLoading && !guests.isError && todayGuests.length === 0 ? <EmptyState label="Nenhum visitante para hoje." /> : null}
              {todayGuests.slice(0, 6).map((guest) => (
                <div key={guest.id} className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-100">{guest.fullName}</p>
                      <p className="mt-1 text-xs text-slate-500">{guest.company ?? "Sem empresa"} · {guest.hostName}</p>
                    </div>
                    <StatusBadge value={guest.status} />
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <StatusBadge value={guest.syncStatus ?? "NOT_REQUIRED"} />
                    <p className="text-xs text-slate-500">{guest.facePhotoUrl ? "Foto facial enviada" : "Sem foto facial"}</p>
                  </div>
                  {guest.lastSyncError ? <p className="mt-2 text-xs text-red-200">{guest.lastSyncError}</p> : null}
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      </div>
    </AdminShell>
  );
}

function integrationSyncTitle(
  event: { personType: string; personId: string; syncStatus: string; message?: string },
  guests: Array<{ id: string; fullName: string }>,
  intelbrasTarget: string
) {
  if (event.message) return event.message;
  if (event.personType === "GUEST") {
    const guestName = guests.find((guest) => guest.id === event.personId)?.fullName ?? `Visitante ${event.personId.slice(0, 8)}`;
    if (event.syncStatus === "SYNCED") return `${guestName} sincronizado com ${intelbrasTarget}`;
    if (event.syncStatus === "SYNCING") return `${guestName} sincronizando com ${intelbrasTarget}`;
    if (event.syncStatus === "SYNC_FAILED") return `${guestName} falhou ao sincronizar com ${intelbrasTarget}`;
    return `${guestName} aguardando sincronização Intelbras`;
  }
  return `${event.personType} ${event.personId.slice(0, 8)} · ${event.syncStatus}`;
}
