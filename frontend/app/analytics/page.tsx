"use client";

import { AdminShell } from "@/components/AdminShell";
import { ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { displayAreaName } from "@/lib/areaLabels";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Badge } from "@/src/components/ui/Badge";
import { analyticsService, AnalyticsFilters, HeatmapPoint } from "@/services/analyticsService";
import { deviceService } from "@/services/deviceService";
import { areaService } from "@/services/areaService";
import { useQuery } from "@tanstack/react-query";
import {
  BarChart, Bar, LineChart, Line, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer
} from "recharts";
import {
  Activity, BarChart2, CalendarClock, CheckCircle2, Clock, Download,
  Fingerprint, IdCard, MapPin, MonitorCog, RefreshCw, ShieldAlert,
  ShieldCheck, TrendingDown, TrendingUp, User, UserRoundPlus, Users, Wifi, WifiOff
} from "lucide-react";
import { useState, useCallback } from "react";

// ─── Date helpers ──────────────────────────────────────────────────────────

function localDate(d = new Date()) {
  const copy = new Date(d.getTime());
  copy.setMinutes(copy.getMinutes() - copy.getTimezoneOffset());
  return copy.toISOString().slice(0, 10);
}
function subDays(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return localDate(d);
}
function startOfMonth() {
  const d = new Date();
  d.setDate(1);
  return localDate(d);
}
function startOfLastMonth() {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return localDate(d);
}
function endOfLastMonth() {
  const d = new Date();
  d.setDate(0);
  return localDate(d);
}

const DATE_PRESETS = [
  { id: "today", label: "Hoje", from: () => localDate(), to: () => localDate() },
  { id: "yesterday", label: "Ontem", from: () => subDays(1), to: () => subDays(1) },
  { id: "7d", label: "7 dias", from: () => subDays(7), to: () => localDate() },
  { id: "30d", label: "30 dias", from: () => subDays(30), to: () => localDate() },
  { id: "thismonth", label: "Este mês", from: startOfMonth, to: () => localDate() },
  { id: "lastmonth", label: "Mês anterior", from: startOfLastMonth, to: endOfLastMonth },
];

// ─── Formatters ────────────────────────────────────────────────────────────

function fmt(n: number | null | undefined) {
  if (n == null) return "—";
  return n.toLocaleString("pt-BR");
}
function fmtCpf(v?: string | null) {
  if (!v) return "—";
  const d = v.replace(/\D/g, "");
  return d.length === 11 ? `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}` : v;
}
function fmtMinutes(min?: number | null) {
  if (min == null) return "—";
  if (min < 60) return `${Math.round(min)}min`;
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}
function fmtTime(iso?: string | null) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR");
}

// ─── CSV export ────────────────────────────────────────────────────────────

function downloadCsv(rows: Record<string, unknown>[], filename: string) {
  if (!rows.length) return;
  const headers = Object.keys(rows[0]);
  const csv = [headers.join(","), ...rows.map((r) => headers.map((h) => JSON.stringify(r[h] ?? "")).join(","))].join("\n");
  const url = URL.createObjectURL(new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8;" }));
  Object.assign(document.createElement("a"), { href: url, download: filename }).click();
  URL.revokeObjectURL(url);
}

// ─── Heatmap ───────────────────────────────────────────────────────────────

function Heatmap({ data }: { data: HeatmapPoint[] }) {
  const maxCount = Math.max(...data.map((d) => d.count), 1);
  const byKey = new Map(data.map((d) => [`${d.dayOfWeek}_${d.hour}`, d.count]));
  const days = ["Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"];

  return (
    <div className="overflow-x-auto">
      <div style={{ display: "grid", gridTemplateColumns: "52px repeat(24, 1fr)", minWidth: 680 }}>
        <div />
        {Array.from({ length: 24 }, (_, h) => (
          <div key={h} className="pb-1 text-center text-[10px] text-slate-500">{String(h).padStart(2, "0")}h</div>
        ))}
        {days.map((day, di) => {
          const dow = di + 1;
          return [
            <div key={`lbl-${dow}`} className="flex items-center justify-end pr-2 text-xs font-medium text-slate-400" style={{ minHeight: 22 }}>
              {day}
            </div>,
            ...Array.from({ length: 24 }, (_, h) => {
              const count = byKey.get(`${dow}_${h}`) ?? 0;
              const opacity = count === 0 ? 0.04 : Math.max(0.12, count / maxCount);
              return (
                <div
                  key={`${dow}_${h}`}
                  title={`${day} ${String(h).padStart(2, "0")}h — ${fmt(count)} acessos`}
                  className="m-[2px] cursor-default rounded-[3px] transition-opacity"
                  style={{ background: `rgba(180,58,75,${opacity})`, minHeight: 20 }}
                />
              );
            })
          ];
        })}
      </div>
      <div className="mt-3 flex items-center gap-2 justify-end">
        <span className="text-[11px] text-slate-500">Baixo</span>
        {[0.08, 0.2, 0.4, 0.65, 1].map((o) => (
          <div key={o} className="h-3 w-6 rounded-sm" style={{ background: `rgba(180,58,75,${o})` }} />
        ))}
        <span className="text-[11px] text-slate-500">Alto</span>
      </div>
    </div>
  );
}

// ─── KPI Card ──────────────────────────────────────────────────────────────

type KpiCardProps = {
  title: string;
  value: number | string;
  icon: React.ElementType;
  description?: string;
  tone?: "default" | "green" | "red" | "amber" | "sky" | "wine";
};

function KpiCard({ title, value, icon: Icon, description, tone = "default" }: KpiCardProps) {
  const colors: Record<string, string> = {
    default: "text-slate-300",
    green: "text-emerald-400",
    red: "text-red-400",
    amber: "text-amber-400",
    sky: "text-sky-400",
    wine: "text-brand-wine",
  };
  const iconBg: Record<string, string> = {
    default: "bg-white/[0.07]",
    green: "bg-emerald-400/10",
    red: "bg-red-500/10",
    amber: "bg-amber-400/10",
    sky: "bg-sky-400/10",
    wine: "bg-brand-wine/10",
  };
  return (
    <div className="enterprise-surface rounded-enterprise p-4">
      <div className="flex items-start justify-between gap-2">
        <div className={`rounded-xl ${iconBg[tone]} p-2`}>
          <Icon className={`h-5 w-5 ${colors[tone]}`} />
        </div>
      </div>
      <p className="mt-3 text-2xl font-bold tracking-tight text-slate-50">
        {typeof value === "number" ? fmt(value) : value}
      </p>
      <p className="mt-0.5 text-sm font-medium text-slate-400">{title}</p>
      {description ? <p className="mt-0.5 text-xs text-slate-500">{description}</p> : null}
    </div>
  );
}

// ─── Section header ────────────────────────────────────────────────────────

function SectionHeader({ title, icon: Icon }: { title: string; icon?: React.ElementType }) {
  return (
    <div className="mb-4 flex items-center gap-2">
      {Icon ? <Icon className="h-4 w-4 text-brand-wine" /> : null}
      <h2 className="text-base font-semibold text-slate-50">{title}</h2>
    </div>
  );
}

// ─── Chart tooltip style ───────────────────────────────────────────────────

const tooltipStyle = {
  contentStyle: { background: "#0f172a", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 10, color: "#e2e8f0", fontSize: 12 },
  labelStyle: { color: "#94a3b8", marginBottom: 4, fontWeight: 600 },
  cursor: { stroke: "rgba(148,163,184,0.25)", strokeWidth: 1 },
};

// ─── Granularity selector ──────────────────────────────────────────────────

const GRANULARITIES = [
  { value: "HOUR" as const, label: "Hora" },
  { value: "DAY" as const, label: "Dia" },
  { value: "WEEK" as const, label: "Semana" },
  { value: "MONTH" as const, label: "Mês" },
];

function GranularityPicker({ value, onChange }: { value: string; onChange: (g: string) => void }) {
  return (
    <div className="flex gap-1">
      {GRANULARITIES.map((g) => (
        <button
          key={g.value}
          type="button"
          onClick={() => onChange(g.value)}
          className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${value === g.value ? "bg-brand-wine text-white" : "bg-white/[0.06] text-slate-400 hover:bg-white/[0.1]"}`}
        >
          {g.label}
        </button>
      ))}
    </div>
  );
}

// ─── Main page ─────────────────────────────────────────────────────────────

export default function AnalyticsPage() {
  const [preset, setPreset] = useState("30d");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [showCustom, setShowCustom] = useState(false);
  const [filters, setFilters] = useState<AnalyticsFilters>(() => {
    const p = DATE_PRESETS.find((x) => x.id === "30d")!;
    return { from: p.from(), to: p.to() };
  });
  const [granularity, setGranularity] = useState<"HOUR" | "DAY" | "WEEK" | "MONTH">("DAY");

  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });

  const analyticsFilters = { ...filters, granularity };

  const overview = useQuery({
    queryKey: ["analytics-overview", filters],
    queryFn: () => analyticsService.overview(filters),
  });
  const timeline = useQuery({
    queryKey: ["analytics-timeline", filters, granularity],
    queryFn: () => analyticsService.timeline(analyticsFilters),
  });
  const heatmap = useQuery({
    queryKey: ["analytics-heatmap", filters],
    queryFn: () => analyticsService.heatmap(filters),
  });
  const authMethods = useQuery({
    queryKey: ["analytics-auth-methods", filters],
    queryFn: () => analyticsService.authMethods(filters),
  });
  const controllers = useQuery({
    queryKey: ["analytics-controllers", filters],
    queryFn: () => analyticsService.controllers(filters),
  });
  const areasStats = useQuery({
    queryKey: ["analytics-areas", filters],
    queryFn: () => analyticsService.areas(filters),
  });
  const usersEmployee = useQuery({
    queryKey: ["analytics-users-emp", filters],
    queryFn: () => analyticsService.users({ ...filters, personType: "EMPLOYEE" }),
  });
  const usersGuest = useQuery({
    queryKey: ["analytics-users-guest", filters],
    queryFn: () => analyticsService.users({ ...filters, personType: "GUEST" }),
  });
  const denials = useQuery({
    queryKey: ["analytics-denials", filters],
    queryFn: () => analyticsService.denials(filters),
  });
  const presence = useQuery({
    queryKey: ["analytics-presence", filters],
    queryFn: () => analyticsService.presence(filters),
  });
  const peaks = useQuery({
    queryKey: ["analytics-peaks", filters],
    queryFn: () => analyticsService.peaks(filters),
  });

  const applyPreset = useCallback((id: string) => {
    setPreset(id);
    setShowCustom(id === "custom");
    if (id !== "custom") {
      const p = DATE_PRESETS.find((x) => x.id === id)!;
      setFilters((prev) => ({ ...prev, from: p.from(), to: p.to() }));
    }
  }, []);

  const applyCustomDates = () => {
    if (customFrom && customTo) {
      setFilters((prev) => ({ ...prev, from: customFrom, to: customTo }));
    }
  };

  const ov = overview.data;
  const auth = authMethods.data;
  const pks = peaks.data;

  const authPieData = auth
    ? [
        { name: "Facial", value: auth.facial, color: "#38bdf8" },
        { name: "Cartão", value: auth.card, color: "#22c55e" },
        { name: "Manual", value: auth.manual, color: "#f59e0b" },
        { name: "Outros", value: auth.other, color: "#94a3b8" },
      ].filter((d) => d.value > 0)
    : [];

  const controllerChartData = (controllers.data ?? []).slice(0, 10).map((c) => ({
    name: c.name.length > 16 ? c.name.slice(0, 14) + "…" : c.name,
    acessos: c.totalAccesses,
    negados: c.denials,
  }));

  const areaChartData = (areasStats.data ?? []).slice(0, 10).map((a) => ({
    name: displayAreaName(a.name).length > 16 ? displayAreaName(a.name).slice(0, 14) + "…" : displayAreaName(a.name),
    acessos: a.totalAccesses,
  }));

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Inteligência Operacional"
        title="Analytics"
        description="Indicadores históricos de acesso, segurança, ocupação e utilização das áreas."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => {
                overview.refetch();
                timeline.refetch();
                heatmap.refetch();
                peaks.refetch();
              }}
              className="flex items-center gap-1.5 rounded-xl border border-white/10 bg-white/[0.055] px-3 py-1.5 text-sm font-medium text-slate-300 hover:bg-white/[0.1]"
            >
              <RefreshCw className="h-4 w-4" />
              Atualizar
            </button>
            <button
              type="button"
              onClick={() => {
                if (ov) {
                  downloadCsv([{
                    "Total Acessos": ov.totalAccesses, Entradas: ov.entries, Saídas: ov.exits,
                    "Usuários Únicos": ov.uniqueUsers, Visitantes: ov.visitors, Colaboradores: ov.employees,
                    "Facial": ov.facialRecognitions, "Cartão": ov.cardAccesses, Negados: ov.denials,
                    "Taxa Sucesso %": ov.successRate, "Online": ov.onlineControllers, "Offline": ov.offlineControllers,
                  }], `analytics-overview-${filters.from}-${filters.to}.csv`);
                }
              }}
              className="flex items-center gap-1.5 rounded-xl border border-white/10 bg-white/[0.055] px-3 py-1.5 text-sm font-medium text-slate-300 hover:bg-white/[0.1]"
            >
              <Download className="h-4 w-4" />
              Exportar CSV
            </button>
          </div>
        }
      />

      {/* ─── Filters ─────────────────────────────────────────────── */}
      <div className="mb-6 space-y-3">
        <div className="flex flex-wrap gap-2">
          {DATE_PRESETS.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => applyPreset(p.id)}
              className={`rounded-xl px-3 py-1.5 text-sm font-medium transition-colors ${preset === p.id ? "bg-brand-wine text-white" : "border border-white/10 bg-white/[0.045] text-slate-400 hover:bg-white/[0.08]"}`}
            >
              {p.label}
            </button>
          ))}
          <button
            type="button"
            onClick={() => { setPreset("custom"); setShowCustom(true); }}
            className={`rounded-xl px-3 py-1.5 text-sm font-medium transition-colors ${preset === "custom" ? "bg-brand-wine text-white" : "border border-white/10 bg-white/[0.045] text-slate-400 hover:bg-white/[0.08]"}`}
          >
            Personalizado
          </button>
        </div>

        {showCustom ? (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1">
              <label className="text-xs text-slate-400">De</label>
              <input
                type="date"
                value={customFrom}
                onChange={(e) => setCustomFrom(e.target.value)}
                className="rounded-lg border border-white/15 bg-white/[0.055] px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-brand-wine"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-slate-400">Até</label>
              <input
                type="date"
                value={customTo}
                onChange={(e) => setCustomTo(e.target.value)}
                className="rounded-lg border border-white/15 bg-white/[0.055] px-3 py-1.5 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-brand-wine"
              />
            </div>
            <button
              type="button"
              onClick={applyCustomDates}
              disabled={!customFrom || !customTo}
              className="rounded-xl bg-brand-wine px-4 py-1.5 text-sm font-medium text-white disabled:opacity-40"
            >
              Aplicar
            </button>
          </div>
        ) : null}

        <div className="flex flex-wrap gap-2">
          {/* Controladora */}
          <select
            value={filters.deviceId ?? ""}
            onChange={(e) => setFilters((prev) => ({ ...prev, deviceId: e.target.value || undefined }))}
            className="rounded-xl border border-white/10 bg-white/[0.045] px-3 py-1.5 text-sm text-slate-300 focus:outline-none focus:ring-1 focus:ring-brand-wine"
          >
            <option value="">Todas as controladoras</option>
            {(devices.data ?? []).map((d) => (
              <option key={d.id} value={d.id}>{d.name}</option>
            ))}
          </select>
          {/* Área */}
          <select
            value={filters.areaId ?? ""}
            onChange={(e) => setFilters((prev) => ({ ...prev, areaId: e.target.value || undefined }))}
            className="rounded-xl border border-white/10 bg-white/[0.045] px-3 py-1.5 text-sm text-slate-300 focus:outline-none focus:ring-1 focus:ring-brand-wine"
          >
            <option value="">Todas as áreas</option>
            {(areas.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>{displayAreaName(a.name)}</option>
            ))}
          </select>
          {/* Tipo de usuário */}
          <select
            value={filters.personType ?? ""}
            onChange={(e) => setFilters((prev) => ({ ...prev, personType: e.target.value || undefined }))}
            className="rounded-xl border border-white/10 bg-white/[0.045] px-3 py-1.5 text-sm text-slate-300 focus:outline-none focus:ring-1 focus:ring-brand-wine"
          >
            <option value="">Todos os tipos</option>
            <option value="EMPLOYEE">Colaboradores</option>
            <option value="GUEST">Visitantes</option>
          </select>
          {/* Método */}
          <select
            value={filters.releaseMethod ?? ""}
            onChange={(e) => setFilters((prev) => ({ ...prev, releaseMethod: e.target.value || undefined }))}
            className="rounded-xl border border-white/10 bg-white/[0.045] px-3 py-1.5 text-sm text-slate-300 focus:outline-none focus:ring-1 focus:ring-brand-wine"
          >
            <option value="">Todos os métodos</option>
            <option value="FACIAL_RECOGNITION">Reconhecimento Facial</option>
            <option value="CARD">Cartão</option>
            <option value="MANUAL_ADMIN_RELEASE">Manual</option>
          </select>
          {filters.from && filters.to ? (
            <Badge tone="slate" className="self-center">{filters.from} → {filters.to}</Badge>
          ) : null}
        </div>
      </div>

      {/* ─── KPI Cards ───────────────────────────────────────────── */}
      {overview.isLoading ? <LoadingState label="Carregando indicadores..." /> : null}
      {overview.isError ? <ErrorState label="Não foi possível carregar o overview." /> : null}

      {ov ? (
        <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <KpiCard title="Total de acessos" value={ov.totalAccesses} icon={Activity} tone="wine" />
          <KpiCard title="Entradas" value={ov.entries} icon={TrendingUp} tone="green" />
          <KpiCard title="Saídas" value={ov.exits} icon={TrendingDown} tone="sky" />
          <KpiCard title="Usuários únicos" value={ov.uniqueUsers} icon={Users} />
          <KpiCard title="Visitantes" value={ov.visitors} icon={UserRoundPlus} />
          <KpiCard title="Colaboradores" value={ov.employees} icon={IdCard} />
          <KpiCard title="Reconhecimentos faciais" value={ov.facialRecognitions} icon={Fingerprint} tone="sky" />
          <KpiCard title="Acessos por cartão" value={ov.cardAccesses} icon={IdCard} tone="amber" />
          <KpiCard title="Tentativas negadas" value={ov.denials} icon={ShieldAlert} tone="red" description="Requer atenção" />
          <KpiCard title="Taxa de sucesso" value={`${ov.successRate}%`} icon={ShieldCheck} tone="green" />
          <KpiCard title="Controladoras online" value={ov.onlineControllers} icon={Wifi} tone="green" />
          <KpiCard title="Controladoras offline" value={ov.offlineControllers} icon={WifiOff} tone={ov.offlineControllers > 0 ? "red" : "default"} />
        </div>
      ) : null}

      {/* ─── Timeline + Auth Methods ─────────────────────────────── */}
      <div className="mb-6 grid gap-6 xl:grid-cols-[1.6fr_0.8fr]">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <BarChart2 className="h-4 w-4 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Entradas × Saídas</h2>
              </div>
              <GranularityPicker value={granularity} onChange={(g) => setGranularity(g as "HOUR" | "DAY" | "WEEK" | "MONTH")} />
            </div>
          </CardHeader>
          <CardContent>
            {timeline.isLoading ? <LoadingState label="Carregando timeline..." /> : null}
            {!timeline.isLoading && (timeline.data ?? []).length === 0 ? (
              <p className="text-center py-8 text-sm text-slate-500">Nenhum dado no período selecionado.</p>
            ) : null}
            {(timeline.data ?? []).length > 0 ? (
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={timeline.data} margin={{ top: 4, right: 16, left: -10, bottom: 0 }}>
                    <CartesianGrid stroke="rgba(255,255,255,0.05)" vertical={false} strokeDasharray="3 3" />
                    <XAxis dataKey="label" stroke="#475569" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} interval="preserveStartEnd" />
                    <YAxis stroke="#475569" tickLine={false} axisLine={false} allowDecimals={false} tick={{ fontSize: 11 }} />
                    <Tooltip {...tooltipStyle} />
                    <Legend wrapperStyle={{ fontSize: 12, paddingTop: 10 }} />
                    <Line type="monotone" dataKey="entries" name="Entradas" stroke="#22c55e" strokeWidth={2.5} dot={false} activeDot={{ r: 4 }} />
                    <Line type="monotone" dataKey="exits" name="Saídas" stroke="#38bdf8" strokeWidth={2.5} dot={false} activeDot={{ r: 4 }} />
                    <Line type="monotone" dataKey="total" name="Total" stroke="#B43A4B" strokeWidth={2} dot={false} activeDot={{ r: 4 }} strokeDasharray="4 2" />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : null}
            {(timeline.data ?? []).length > 0 ? (
              <div className="mt-3 flex justify-end">
                <button
                  type="button"
                  onClick={() => downloadCsv(timeline.data!.map((r) => ({ Período: r.label, Entradas: r.entries, Saídas: r.exits, Total: r.total })), `timeline-${filters.from}-${filters.to}.csv`)}
                  className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300"
                >
                  <Download className="h-3 w-3" /> CSV
                </button>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Fingerprint className="h-4 w-4 text-brand-wine" />
              <h2 className="text-base font-semibold text-slate-50">Método de autenticação</h2>
            </div>
          </CardHeader>
          <CardContent>
            {authMethods.isLoading ? <LoadingState label="Carregando..." /> : null}
            {auth && authPieData.length > 0 ? (
              <>
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie data={authPieData} cx="50%" cy="50%" outerRadius={80} dataKey="value" label={({ name, percent }) => `${name} ${((percent ?? 0) * 100).toFixed(0)}%`} labelLine={false} fontSize={11}>
                        {authPieData.map((entry) => (
                          <Cell key={entry.name} fill={entry.color} />
                        ))}
                      </Pie>
                      <Tooltip {...tooltipStyle} />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
                <div className="mt-2 space-y-1.5">
                  {authPieData.map((d) => (
                    <div key={d.name} className="flex items-center justify-between text-xs">
                      <div className="flex items-center gap-2">
                        <div className="h-2.5 w-2.5 rounded-full" style={{ background: d.color }} />
                        <span className="text-slate-400">{d.name}</span>
                      </div>
                      <span className="font-medium text-slate-200">{fmt(d.value)}</span>
                    </div>
                  ))}
                </div>
              </>
            ) : null}
            {auth && authPieData.length === 0 ? (
              <p className="py-8 text-center text-sm text-slate-500">Sem dados de autenticação no período.</p>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* ─── Controllers + Areas bar charts ─────────────────────── */}
      <div className="mb-6 grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <MonitorCog className="h-4 w-4 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Acessos por controladora</h2>
              </div>
              {(controllers.data ?? []).length > 0 ? (
                <button type="button" onClick={() => downloadCsv((controllers.data ?? []).map((c) => ({ Controladora: c.name, Área: c.areaName, Total: c.totalAccesses, Negados: c.denials })), `controllers-${filters.from}.csv`)} className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300">
                  <Download className="h-3 w-3" /> CSV
                </button>
              ) : null}
            </div>
          </CardHeader>
          <CardContent>
            {controllers.isLoading ? <LoadingState label="Carregando..." /> : null}
            {!controllers.isLoading && controllerChartData.length === 0 ? <p className="py-6 text-center text-sm text-slate-500">Nenhuma controladora com dados.</p> : null}
            {controllerChartData.length > 0 ? (
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={controllerChartData} layout="vertical" margin={{ top: 0, right: 12, left: 4, bottom: 0 }}>
                    <CartesianGrid stroke="rgba(255,255,255,0.05)" horizontal={false} />
                    <XAxis type="number" stroke="#475569" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} allowDecimals={false} />
                    <YAxis dataKey="name" type="category" stroke="#475569" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} width={90} />
                    <Tooltip {...tooltipStyle} />
                    <Bar dataKey="acessos" name="Acessos" fill="#B43A4B" radius={[0, 4, 4, 0]} />
                    <Bar dataKey="negados" name="Negados" fill="#ef4444" radius={[0, 4, 4, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <MapPin className="h-4 w-4 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Acessos por área</h2>
              </div>
              {areaChartData.length > 0 ? (
                <button type="button" onClick={() => downloadCsv((areasStats.data ?? []).map((a) => ({ Área: a.name, Total: a.totalAccesses })), `areas-${filters.from}.csv`)} className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300">
                  <Download className="h-3 w-3" /> CSV
                </button>
              ) : null}
            </div>
          </CardHeader>
          <CardContent>
            {areasStats.isLoading ? <LoadingState label="Carregando..." /> : null}
            {!areasStats.isLoading && areaChartData.length === 0 ? <p className="py-6 text-center text-sm text-slate-500">Nenhuma área com dados.</p> : null}
            {areaChartData.length > 0 ? (
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={areaChartData} layout="vertical" margin={{ top: 0, right: 12, left: 4, bottom: 0 }}>
                    <CartesianGrid stroke="rgba(255,255,255,0.05)" horizontal={false} />
                    <XAxis type="number" stroke="#475569" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} allowDecimals={false} />
                    <YAxis dataKey="name" type="category" stroke="#475569" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} width={90} />
                    <Tooltip {...tooltipStyle} />
                    <Bar dataKey="acessos" name="Acessos" fill="#38bdf8" radius={[0, 4, 4, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* ─── Heatmap ─────────────────────────────────────────────── */}
      <Card className="mb-6">
        <CardHeader>
          <div className="flex items-center gap-2">
            <CalendarClock className="h-4 w-4 text-brand-wine" />
            <h2 className="text-base font-semibold text-slate-50">Mapa de calor — Dia × Horário</h2>
          </div>
          <p className="mt-0.5 text-xs text-slate-500">Intensidade de acessos por hora e dia da semana</p>
        </CardHeader>
        <CardContent>
          {heatmap.isLoading ? <LoadingState label="Carregando heatmap..." /> : null}
          {heatmap.isError ? <ErrorState label="Não foi possível carregar o heatmap." /> : null}
          {heatmap.data ? <Heatmap data={heatmap.data} /> : null}
        </CardContent>
      </Card>

      {/* ─── Peaks ───────────────────────────────────────────────── */}
      {pks ? (
        <div className="mb-6">
          <SectionHeader title="Indicadores de pico" icon={TrendingUp} />
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            {[
              { label: "Pico de entradas", data: pks.peakEntry, icon: TrendingUp, tone: "green" as const, sub: "entradas" },
              { label: "Pico de saídas", data: pks.peakExit, icon: TrendingDown, tone: "sky" as const, sub: "saídas" },
              { label: "Dia mais movimentado", data: pks.busiestDay, icon: CalendarClock, tone: "wine" as const, sub: "acessos" },
              { label: "Semana mais movimentada", data: pks.busiestWeek, icon: BarChart2, tone: "amber" as const, sub: "acessos" },
              { label: "Mês mais movimentado", data: pks.busiestMonth, icon: Activity, tone: "default" as const, sub: "acessos" },
            ].map(({ label, data, icon: Icon, tone, sub }) => (
              <div key={label} className="enterprise-surface rounded-enterprise p-4">
                <div className="flex items-center gap-2">
                  <Icon className="h-4 w-4 text-brand-wine" />
                  <p className="text-xs text-slate-400">{label}</p>
                </div>
                <p className={`mt-2 text-lg font-bold ${tone === "green" ? "text-emerald-400" : tone === "sky" ? "text-sky-400" : tone === "amber" ? "text-amber-400" : tone === "wine" ? "text-brand-wine" : "text-slate-200"}`}>
                  {data.label}
                </p>
                <p className="text-xs text-slate-500">{fmt(data.count)} {sub}</p>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {/* ─── Rankings ────────────────────────────────────────────── */}
      <div className="mb-6 grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <IdCard className="h-4 w-4 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Top colaboradores</h2>
              </div>
              {(usersEmployee.data ?? []).length > 0 ? (
                <button type="button" onClick={() => downloadCsv((usersEmployee.data ?? []).map((u, i) => ({ "#": i + 1, Nome: u.name, CPF: u.cpf, Acessos: u.accessCount })), `top-colaboradores.csv`)} className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300">
                  <Download className="h-3 w-3" /> CSV
                </button>
              ) : null}
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {usersEmployee.isLoading ? <div className="p-5"><LoadingState label="Carregando..." /></div> : null}
            {!usersEmployee.isLoading && (usersEmployee.data ?? []).length === 0 ? <p className="p-5 text-sm text-slate-500">Sem dados no período.</p> : null}
            {(usersEmployee.data ?? []).length > 0 ? (
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-slate-900/80 backdrop-blur">
                    <tr>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">#</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Nome</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500 font-mono">CPF</th>
                      <th className="px-4 py-2.5 text-right text-xs font-medium text-slate-500">Acessos</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(usersEmployee.data ?? []).map((u, i) => (
                      <tr key={`${u.personId}-${i}`} className="border-t border-white/5 hover:bg-white/[0.03]">
                        <td className="px-4 py-2 text-xs font-medium text-slate-500">{i + 1}</td>
                        <td className="px-4 py-2 text-slate-200">{u.name ?? "—"}</td>
                        <td className="px-4 py-2 font-mono text-xs text-slate-400">{fmtCpf(u.cpf)}</td>
                        <td className="px-4 py-2 text-right font-bold text-brand-wine">{fmt(u.accessCount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <UserRoundPlus className="h-4 w-4 text-brand-wine" />
                <h2 className="text-base font-semibold text-slate-50">Top visitantes</h2>
              </div>
              {(usersGuest.data ?? []).length > 0 ? (
                <button type="button" onClick={() => downloadCsv((usersGuest.data ?? []).map((u, i) => ({ "#": i + 1, Nome: u.name, CPF: u.cpf, Acessos: u.accessCount })), `top-visitantes.csv`)} className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300">
                  <Download className="h-3 w-3" /> CSV
                </button>
              ) : null}
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {usersGuest.isLoading ? <div className="p-5"><LoadingState label="Carregando..." /></div> : null}
            {!usersGuest.isLoading && (usersGuest.data ?? []).length === 0 ? <p className="p-5 text-sm text-slate-500">Sem dados no período.</p> : null}
            {(usersGuest.data ?? []).length > 0 ? (
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-slate-900/80 backdrop-blur">
                    <tr>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">#</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Nome</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500 font-mono">CPF</th>
                      <th className="px-4 py-2.5 text-right text-xs font-medium text-slate-500">Acessos</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(usersGuest.data ?? []).map((u, i) => (
                      <tr key={`${u.personId}-${i}`} className="border-t border-white/5 hover:bg-white/[0.03]">
                        <td className="px-4 py-2 text-xs font-medium text-slate-500">{i + 1}</td>
                        <td className="px-4 py-2 text-slate-200">{u.name ?? "—"}</td>
                        <td className="px-4 py-2 font-mono text-xs text-slate-400">{fmtCpf(u.cpf)}</td>
                        <td className="px-4 py-2 text-right font-bold text-brand-wine">{fmt(u.accessCount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* ─── Denials + Presence ──────────────────────────────────── */}
      <div className="mb-6 grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <ShieldAlert className="h-4 w-4 text-red-400" />
                <h2 className="text-base font-semibold text-slate-50">Análise de negativas</h2>
              </div>
              <div className="flex items-center gap-3">
                {denials.data ? <Badge tone="red">{fmt(denials.data.total)} negadas</Badge> : null}
                {(denials.data?.recent ?? []).length > 0 ? (
                  <button type="button" onClick={() => downloadCsv((denials.data!.recent).map((d) => ({ "Data/Hora": fmtTime(d.eventTime), Pessoa: d.personName, CPF: d.personCpf, Controladora: d.deviceName, Área: d.areaName, Motivo: d.decisionReason, Método: d.releaseMethod })), `negativas-${filters.from}.csv`)} className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300">
                    <Download className="h-3 w-3" /> CSV
                  </button>
                ) : null}
              </div>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {denials.isLoading ? <div className="p-5"><LoadingState label="Carregando..." /></div> : null}
            {!denials.isLoading && (denials.data?.recent ?? []).length === 0 ? <p className="p-5 text-sm text-slate-500">Nenhuma negativa no período.</p> : null}
            {(denials.data?.recent ?? []).length > 0 ? (
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-slate-900/80 backdrop-blur">
                    <tr>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Data/Hora</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Pessoa</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Controladora</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Motivo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(denials.data?.recent ?? []).map((d) => (
                      <tr key={d.id} className="border-t border-white/5 hover:bg-white/[0.03]">
                        <td className="px-4 py-2 text-xs whitespace-nowrap text-slate-400">{fmtTime(d.eventTime)}</td>
                        <td className="px-4 py-2 text-slate-200">
                          <p className="font-medium">{d.personName ?? "—"}</p>
                          <p className="text-xs text-slate-500">{fmtCpf(d.personCpf)}</p>
                        </td>
                        <td className="px-4 py-2 text-xs text-slate-400">{d.deviceName ?? "—"}</td>
                        <td className="px-4 py-2 text-xs text-red-300">{d.decisionReason ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <User className="h-4 w-4 text-emerald-400" />
                <h2 className="text-base font-semibold text-slate-50">Presença atual</h2>
              </div>
              {presence.data ? <Badge tone="green">{fmt(presence.data.length)} pessoas dentro</Badge> : null}
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {presence.isLoading ? <div className="p-5"><LoadingState label="Carregando..." /></div> : null}
            {!presence.isLoading && (presence.data ?? []).length === 0 ? <p className="p-5 text-sm text-slate-500">Ninguém identificado como presente.</p> : null}
            {(presence.data ?? []).length > 0 ? (
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-slate-900/80 backdrop-blur">
                    <tr>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Pessoa</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Área</th>
                      <th className="px-4 py-2.5 text-left text-xs font-medium text-slate-500">Entrada</th>
                      <th className="px-4 py-2.5 text-right text-xs font-medium text-slate-500">Tempo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(presence.data ?? []).map((p, i) => (
                      <tr key={`${p.personId}-${i}`} className="border-t border-white/5 hover:bg-white/[0.03]">
                        <td className="px-4 py-2 text-slate-200">
                          <p className="font-medium">{p.personName ?? "—"}</p>
                          <p className="text-xs text-slate-500">{fmtCpf(p.personCpf)}</p>
                        </td>
                        <td className="px-4 py-2 text-xs text-slate-400">{displayAreaName(p.areaName)}</td>
                        <td className="px-4 py-2 text-xs whitespace-nowrap text-slate-400">{fmtTime(p.entryTime)}</td>
                        <td className="px-4 py-2 text-right text-xs font-medium text-emerald-400">{fmtMinutes(p.minutesInside)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* ─── Dwell Time ──────────────────────────────────────────── */}
      {ov && (ov.avgDwellMinutes != null) ? (
        <div className="mb-6">
          <SectionHeader title="Tempo médio de permanência" icon={Clock} />
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="enterprise-surface rounded-enterprise p-5 text-center">
              <p className="text-xs text-slate-400">Tempo médio</p>
              <p className="mt-2 text-3xl font-bold text-sky-400">{fmtMinutes(ov.avgDwellMinutes)}</p>
            </div>
            <div className="enterprise-surface rounded-enterprise p-5 text-center">
              <p className="text-xs text-slate-400">Tempo máximo</p>
              <p className="mt-2 text-3xl font-bold text-amber-400">{fmtMinutes(ov.maxDwellMinutes)}</p>
            </div>
            <div className="enterprise-surface rounded-enterprise p-5 text-center">
              <p className="text-xs text-slate-400">Tempo mínimo</p>
              <p className="mt-2 text-3xl font-bold text-emerald-400">{fmtMinutes(ov.minDwellMinutes)}</p>
            </div>
          </div>
        </div>
      ) : null}

      {/* ─── Controller monitoring ───────────────────────────────── */}
      {(controllers.data ?? []).length > 0 ? (
        <div className="mb-6">
          <SectionHeader title="Monitoramento das controladoras" icon={MonitorCog} />
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {(controllers.data ?? []).map((c) => {
              const isOnline = c.status === "ONLINE";
              return (
                <div key={c.id} className="enterprise-surface rounded-enterprise p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-slate-100">{c.name}</p>
                      <p className="text-xs text-slate-500">{displayAreaName(c.areaName)}</p>
                    </div>
                    <div className={`flex-shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ${isOnline ? "bg-emerald-400/10 text-emerald-400" : "bg-red-500/10 text-red-400"}`}>
                      {isOnline ? "Online" : "Offline"}
                    </div>
                  </div>
                  <div className="mt-3 grid grid-cols-2 gap-2">
                    <div>
                      <p className="text-xs text-slate-500">Acessos</p>
                      <p className="text-lg font-bold text-slate-100">{fmt(c.totalAccesses)}</p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-500">Negados</p>
                      <p className={`text-lg font-bold ${c.denials > 0 ? "text-red-400" : "text-slate-100"}`}>{fmt(c.denials)}</p>
                    </div>
                  </div>
                  {c.lastEvent ? (
                    <p className="mt-2 text-[11px] text-slate-500">Último evento: {fmtTime(c.lastEvent)}</p>
                  ) : null}
                  {c.communicationFailures > 0 ? (
                    <p className="mt-1 text-[11px] text-amber-400">{c.communicationFailures} falha(s) de comunicação</p>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>
      ) : null}

      {/* ─── Export row ──────────────────────────────────────────── */}
      <Card>
        <CardContent>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <Download className="h-4 w-4 text-brand-wine" />
              <span className="text-sm font-semibold text-slate-200">Exportações disponíveis</span>
            </div>
            <div className="flex flex-wrap gap-2">
              {[
                {
                  label: "Overview KPIs",
                  fn: () => ov && downloadCsv([{ "Total": ov.totalAccesses, "Entradas": ov.entries, "Saídas": ov.exits, "Únicos": ov.uniqueUsers, "Visitantes": ov.visitors, "Colaboradores": ov.employees, "Facial": ov.facialRecognitions, "Cartão": ov.cardAccesses, "Negados": ov.denials, "Sucesso %": ov.successRate }], `overview-${filters.from}.csv`),
                },
                {
                  label: "Timeline",
                  fn: () => timeline.data && downloadCsv(timeline.data.map((r) => ({ Período: r.label, Entradas: r.entries, Saídas: r.exits, Total: r.total })), `timeline-${filters.from}.csv`),
                },
                {
                  label: "Controladoras",
                  fn: () => controllers.data && downloadCsv(controllers.data.map((c) => ({ Nome: c.name, Área: c.areaName, Status: c.status, Acessos: c.totalAccesses, Negados: c.denials })), `controllers-${filters.from}.csv`),
                },
                {
                  label: "Top Colaboradores",
                  fn: () => usersEmployee.data && downloadCsv(usersEmployee.data.map((u, i) => ({ "#": i + 1, Nome: u.name, CPF: u.cpf, Acessos: u.accessCount })), `top-colaboradores.csv`),
                },
                {
                  label: "Top Visitantes",
                  fn: () => usersGuest.data && downloadCsv(usersGuest.data.map((u, i) => ({ "#": i + 1, Nome: u.name, CPF: u.cpf, Acessos: u.accessCount })), `top-visitantes.csv`),
                },
                {
                  label: "Negativas",
                  fn: () => denials.data && downloadCsv(denials.data.recent.map((d) => ({ "Data/Hora": fmtTime(d.eventTime), Pessoa: d.personName, CPF: d.personCpf, Controladora: d.deviceName, Área: d.areaName, Motivo: d.decisionReason })), `negativas-${filters.from}.csv`),
                },
                {
                  label: "Presença",
                  fn: () => presence.data && downloadCsv(presence.data.map((p) => ({ Pessoa: p.personName, CPF: p.personCpf, Área: p.areaName, Entrada: fmtTime(p.entryTime), "Tempo (min)": p.minutesInside })), `presenca.csv`),
                },
              ].map(({ label, fn }) => (
                <button
                  key={label}
                  type="button"
                  onClick={fn}
                  className="flex items-center gap-1.5 rounded-lg border border-white/10 bg-white/[0.045] px-3 py-1.5 text-xs font-medium text-slate-400 hover:bg-white/[0.08] hover:text-slate-200"
                >
                  <Download className="h-3 w-3" />
                  {label}
                </button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>
    </AdminShell>
  );
}
