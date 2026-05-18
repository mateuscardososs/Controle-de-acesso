"use client";

import { AdminShell } from "@/components/AdminShell";
import { ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { StatCard } from "@/components/StatCard";
import { dashboardService } from "@/services/dashboardService";
import { useQuery } from "@tanstack/react-query";
import { CalendarClock, IdCard, MonitorCog, ShieldAlert } from "lucide-react";

export default function DashboardPage() {
  const summary = useQuery({ queryKey: ["dashboard-summary"], queryFn: dashboardService.summary });

  return (
    <AdminShell>
      <PageHeader title="Dashboard" description="Indicadores operacionais em tempo real da API." />
      {summary.isLoading ? <LoadingState label="Carregando indicadores..." /> : null}
      {summary.isError ? <ErrorState label="Não foi possível carregar o dashboard." /> : null}
      {!summary.isLoading && !summary.isError ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <StatCard title="Total de colaboradores" value={summary.data?.totalEmployees ?? 0} icon={IdCard} />
          <StatCard title="Total de dispositivos" value={summary.data?.totalDevices ?? 0} icon={MonitorCog} />
          <StatCard title="Eventos hoje" value={summary.data?.todayEvents ?? 0} icon={CalendarClock} />
          <StatCard title="Acessos negados" value={summary.data?.deniedAccesses ?? 0} icon={ShieldAlert} />
        </div>
      ) : null}
    </AdminShell>
  );
}
