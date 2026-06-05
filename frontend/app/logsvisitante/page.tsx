"use client";

import { FormEvent, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw, Search, UserCheck } from "lucide-react";
import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { displayAreaName } from "@/lib/areaLabels";
import { formatCpfDisplay, formatCpfInput } from "@/lib/cpf";
import { accessEventService, AccessEventFilters } from "@/services/accessEventService";
import { configService } from "@/services/configService";
import { Badge } from "@/src/components/ui/Badge";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Input, Select } from "@/src/components/ui/Input";
import { StatusBadge } from "@/src/components/shared/StatusBadge";

type VisitorLogFilters = Pick<AccessEventFilters, "personName" | "personCpf" | "invitedLounge">;

const emptyFilters: VisitorLogFilters = {
  personName: "",
  personCpf: "",
  invitedLounge: ""
};

export default function VisitorLogsPage() {
  const [draftFilters, setDraftFilters] = useState<VisitorLogFilters>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<VisitorLogFilters>(emptyFilters);
  const lounges = useQuery({ queryKey: ["config", "lounges"], queryFn: configService.lounges });
  const events = useQuery({
    queryKey: ["access-events", "visitor-logs", appliedFilters],
    queryFn: () => accessEventService.listAccessEvents({ ...appliedFilters, page: 0, size: 30 }),
    refetchInterval: 5000
  });

  const rows = events.data?.content ?? [];

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAppliedFilters(draftFilters);
  }

  function clearFilters() {
    setDraftFilters(emptyFilters);
    setAppliedFilters(emptyFilters);
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Entrada"
        title="Consulta de visitante"
        description="Visão rápida para orientar o fluxo na catraca."
        actions={
          <Button variant="secondary" icon={RefreshCw} loading={events.isFetching} onClick={() => events.refetch()}>
            Atualizar
          </Button>
        }
      />

      <Card className="mb-5">
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <UserCheck className="h-5 w-5 text-brand-wine" />
              <h2 className="text-base font-semibold text-slate-50">Busca rápida</h2>
            </div>
            <Badge tone="slate">{events.data?.totalElements ?? 0} eventos</Badge>
          </div>
        </CardHeader>
        <CardContent>
          <form onSubmit={applyFilters} className="grid gap-4 lg:grid-cols-[1fr_180px_220px_auto] lg:items-end">
            <Input label="Nome" value={draftFilters.personName ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, personName: event.target.value }))} />
            <Input label="CPF" value={draftFilters.personCpf ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, personCpf: formatCpfInput(event.target.value) }))} inputMode="numeric" />
            <Select label="Camarote" value={draftFilters.invitedLounge ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, invitedLounge: event.target.value }))}>
              <option value="">Todos</option>
              {(lounges.data ?? []).map((lounge) => <option key={lounge} value={lounge}>{displayAreaName(lounge)}</option>)}
            </Select>
            <div className="flex gap-2">
              <Button type="submit" icon={Search} className="h-10 px-4">Buscar</Button>
              <Button type="button" variant="secondary" className="h-10 px-4" onClick={clearFilters}>Limpar</Button>
            </div>
          </form>
        </CardContent>
      </Card>

      {events.isLoading ? <LoadingState label="Carregando eventos recentes..." /> : null}
      {events.isError ? <ErrorState label="Não foi possível carregar a consulta de visitantes. Verifique sua sessão e tente novamente." /> : null}
      {!events.isLoading && !events.isError && rows.length === 0 ? (
        <EmptyState label="Nenhum visitante encontrado." description="Use nome, CPF ou camarote para consultar eventos recentes." />
      ) : null}

      {!events.isLoading && !events.isError && rows.length > 0 ? (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-base">
              <thead className="border-b border-white/10 bg-white/[0.055] text-xs uppercase tracking-[0.16em] text-slate-400">
                <tr>
                  <th className="px-5 py-4 font-semibold">Horário</th>
                  <th className="px-5 py-4 font-semibold">Pessoa</th>
                  <th className="px-5 py-4 font-semibold">CPF</th>
                  <th className="px-5 py-4 font-semibold">Camarote</th>
                  <th className="px-5 py-4 font-semibold">Passagem</th>
                  <th className="px-5 py-4 font-semibold">Reconhecimento</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/10">
                {rows.map((event) => (
                  <tr key={event.id} className="bg-white/[0.025] transition hover:bg-white/[0.07]">
                    <td className="whitespace-nowrap px-5 py-5 font-medium text-slate-100">{formatDate(event.occurredAt ?? event.eventTime)}</td>
                    <td className="px-5 py-5 text-lg font-semibold text-slate-50">{personLabel(event)}</td>
                    <td className="whitespace-nowrap px-5 py-5 font-mono text-sm text-slate-200">{formatCpfDisplay(event.personCpf)}</td>
                    <td className="whitespace-nowrap px-5 py-5 text-slate-200">{event.invitedLounge ? displayAreaName(event.invitedLounge) : "Não informado"}</td>
                    <td className="px-5 py-5"><StatusBadge value={event.passageStatus} /></td>
                    <td className="px-5 py-5"><StatusBadge value={event.recognitionStatus} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      ) : null}
    </AdminShell>
  );
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "medium" }) : "Não informado";
}

function personLabel(event: { personName?: string | null; rawCardName?: string | null; externalUserId?: string | null }) {
  if (event.personName) return event.personName;
  if (event.rawCardName) return event.rawCardName;
  if (event.externalUserId) return `Usuário ${event.externalUserId}`;
  return "Usuário não identificado";
}
