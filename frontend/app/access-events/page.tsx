"use client";

import { FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Eraser, FileClock, FileDown, Plus, RefreshCcw, Search, ShieldCheck, Trash2 } from "lucide-react";
import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { displayAreaName } from "@/lib/areaLabels";
import { formatCpfDisplay, formatCpfInput } from "@/lib/cpf";
import { apiErrorMessage } from "@/lib/errors";
import { accessEventService, AccessEvent, AccessEventFilters, ManualReleasePayload } from "@/services/accessEventService";
import { adminCleanupService } from "@/services/adminCleanupService";
import { areaService } from "@/services/areaService";
import { authService } from "@/services/authService";
import { configService } from "@/services/configService";
import { deviceService } from "@/services/deviceService";
import { Badge } from "@/src/components/ui/Badge";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Input, Select, Textarea } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge, humanizeStatus } from "@/src/components/shared/StatusBadge";

const pageSize = 10;

const eventTypeOptions = ["ENTRY", "EXIT", "ACCESS_DENIED", "COMMUNICATION_FAILURE", "MANUAL_ADMIN_RELEASE"];
const resultOptions = ["ALLOWED", "DENIED", "ERROR"];
const recognitionOptions = ["RECOGNIZED", "NOT_RECOGNIZED", "NOT_APPLICABLE", "ERROR"];
const passageOptions = ["PASSED", "NOT_PASSED", "NOT_APPLICABLE", "ERROR"];
const releaseOptions = ["FACIAL_RECOGNITION", "CARD", "MANUAL_ADMIN_RELEASE", "UNKNOWN"];
const originOptions = ["INTELBRAS_REAL", "INTELBRAS_SIMULATOR", "SIMULATION", "MANUAL_ADMIN_RELEASE"];

type FilterForm = Omit<AccessEventFilters, "page" | "size" | "manualOnly"> & {
  manualOnly: boolean;
};

const emptyFilters: FilterForm = {
  personName: "",
  personCpf: "",
  invitedDay: "",
  invitedLounge: "",
  startDate: "",
  endDate: "",
  deviceId: "",
  areaId: "",
  eventType: "",
  accessResult: "",
  recognitionStatus: "",
  passageStatus: "",
  releaseMethod: "",
  origin: "",
  manualOnly: false
};

export default function AccessEventsPage() {
  const queryClient = useQueryClient();
  const [draftFilters, setDraftFilters] = useState<FilterForm>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<FilterForm>(emptyFilters);
  const [page, setPage] = useState(0);
  const [manualOpen, setManualOpen] = useState(false);
  const [manualConfirmed, setManualConfirmed] = useState(false);
  const [cleanupOpen, setCleanupOpen] = useState(false);
  const [cleanupConfirmation, setCleanupConfirmation] = useState("");
  const [cleanupMessage, setCleanupMessage] = useState("");
  const [manualForm, setManualForm] = useState<ManualReleasePayload>({
    personName: "",
    personCpf: "",
    deviceId: "",
    reason: "",
    operatorObservation: ""
  });

  const user = useQuery({ queryKey: ["me"], queryFn: authService.me, retry: false });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const lounges = useQuery({ queryKey: ["config", "lounges"], queryFn: configService.lounges });
  const canManualRelease = user.data?.role === "ADMIN" || user.data?.role === "HR";
  const canCleanup = user.data?.role === "ADMIN";

  const eventQuery = useQuery({
    queryKey: ["access-events", "logs", appliedFilters, page],
    queryFn: () => accessEventService.listAccessEvents(toApiFilters(appliedFilters, page)),
    placeholderData: (previous) => previous
  });

  const manualRelease = useMutation({
    mutationFn: accessEventService.createManualRelease,
    onSuccess: () => {
      setManualOpen(false);
      setManualConfirmed(false);
      setManualForm({ personName: "", personCpf: "", deviceId: "", reason: "", operatorObservation: "" });
      setPage(0);
      queryClient.invalidateQueries({ queryKey: ["access-events"] });
    }
  });

  const cleanupAccessEvents = useMutation({
    mutationFn: () => adminCleanupService.accessEvents(cleanupConfirmation),
    onSuccess: (response) => {
      setCleanupOpen(false);
      setCleanupConfirmation("");
      setCleanupMessage(response.message);
      setPage(0);
      queryClient.invalidateQueries({ queryKey: ["access-events"] });
    },
    onError: (error) => setCleanupMessage(apiErrorMessage(error, "Não foi possível limpar os eventos."))
  });
  const exportEvents = useMutation({
    mutationFn: () => accessEventService.exportCsv(toExportFilters(appliedFilters)),
    onSuccess: ({ blob, filename }) => {
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    }
  });

  const deviceById = useMemo(() => new Map((devices.data ?? []).map((device) => [device.id, device])), [devices.data]);
  const areaById = useMemo(() => new Map((areas.data ?? []).map((area) => [area.id, area])), [areas.data]);
  const pageData = eventQuery.data;
  const rows = pageData?.content ?? [];

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setAppliedFilters(draftFilters);
  }

  function clearFilters() {
    setDraftFilters(emptyFilters);
    setAppliedFilters(emptyFilters);
    setPage(0);
  }

  function submitManualRelease(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    manualRelease.mutate({
      ...manualForm,
      personCpf: manualForm.personCpf?.trim() || undefined,
      operatorObservation: manualForm.operatorObservation?.trim() || undefined
    });
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Auditoria operacional"
        title="Logs de Eventos"
        description="Consulta paginada dos eventos de acesso, decisões das catracas e liberações manuais."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {canManualRelease ? (
              <Button icon={Plus} onClick={() => setManualOpen(true)}>
                Liberação manual
              </Button>
            ) : null}
            {canCleanup ? (
              <Button variant="danger" icon={Trash2} onClick={() => { setCleanupOpen(true); setCleanupMessage(""); }}>
                Limpar lista
              </Button>
            ) : null}
            <Button variant="secondary" icon={FileDown} loading={exportEvents.isPending} onClick={() => exportEvents.mutate()}>
              Exportar eventos
            </Button>
            <Button variant="secondary" icon={RefreshCcw} loading={eventQuery.isFetching} onClick={() => eventQuery.refetch()}>
              Atualizar
            </Button>
          </div>
        }
      />

      <Card className="mb-5">
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Search className="h-5 w-5 text-brand-wine" />
              <h2 className="text-base font-semibold text-slate-50">Filtros</h2>
            </div>
            <Badge tone="slate">{pageData?.totalElements ?? 0} registros</Badge>
          </div>
        </CardHeader>
        <CardContent>
          <form onSubmit={applyFilters} className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
              <Input label="Nome da pessoa" value={draftFilters.personName ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, personName: event.target.value }))} />
              <Input label="CPF" value={draftFilters.personCpf ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, personCpf: formatCpfInput(event.target.value) }))} inputMode="numeric" />
              <Input label="Dia convidado" type="date" value={draftFilters.invitedDay ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, invitedDay: event.target.value }))} />
              <Select label="Camarote" value={draftFilters.invitedLounge ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, invitedLounge: event.target.value }))}>
                <option value="">Todos</option>
                {(lounges.data ?? []).map((lounge) => <option key={lounge} value={lounge}>{displayAreaName(lounge)}</option>)}
              </Select>
              <Input label="Data inicial" type="datetime-local" value={draftFilters.startDate ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, startDate: event.target.value }))} />
              <Input label="Data final" type="datetime-local" value={draftFilters.endDate ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, endDate: event.target.value }))} />
              <Select label="Catraca/device" value={draftFilters.deviceId ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, deviceId: event.target.value }))}>
                <option value="">Todas</option>
                {(devices.data ?? []).map((device) => (
                  <option key={device.id} value={device.id}>{device.name}</option>
                ))}
              </Select>
              <Select label="Área" value={draftFilters.areaId ?? ""} onChange={(event) => setDraftFilters((current) => ({ ...current, areaId: event.target.value }))}>
                <option value="">Todas</option>
                {(areas.data ?? []).map((area) => (
                  <option key={area.id} value={area.id}>{displayAreaName(area.name)}</option>
                ))}
              </Select>
              <EnumSelect label="Tipo de evento" value={draftFilters.eventType ?? ""} options={eventTypeOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, eventType: value }))} />
              <EnumSelect label="Resultado" value={draftFilters.accessResult ?? ""} options={resultOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, accessResult: value as FilterForm["accessResult"] }))} />
              <EnumSelect label="Reconhecimento" value={draftFilters.recognitionStatus ?? ""} options={recognitionOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, recognitionStatus: value }))} />
              <EnumSelect label="Passagem" value={draftFilters.passageStatus ?? ""} options={passageOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, passageStatus: value }))} />
              <EnumSelect label="Método/liberação" value={draftFilters.releaseMethod ?? ""} options={releaseOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, releaseMethod: value }))} />
              <EnumSelect label="Origem" value={draftFilters.origin ?? ""} options={originOptions} onChange={(value) => setDraftFilters((current) => ({ ...current, origin: value }))} />
            </div>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <label className="inline-flex items-center gap-2 text-sm font-medium text-slate-300">
                <input
                  type="checkbox"
                  checked={draftFilters.manualOnly}
                  onChange={(event) => setDraftFilters((current) => ({ ...current, manualOnly: event.target.checked }))}
                  className="h-4 w-4 rounded border-white/15 bg-white/[0.055] accent-red-700"
                />
                Apenas liberações manuais
              </label>
              <div className="flex flex-wrap gap-2">
                <Button type="submit" icon={Search}>Aplicar filtros</Button>
                <Button variant="secondary" icon={Eraser} onClick={clearFilters}>Limpar filtros</Button>
              </div>
            </div>
          </form>
        </CardContent>
      </Card>

      {eventQuery.isLoading ? <LoadingState label="Carregando logs de eventos..." /> : null}
      {cleanupMessage ? <div className="mb-4 rounded-xl border border-white/10 bg-white/[0.055] px-4 py-3 text-sm font-medium text-slate-200">{cleanupMessage}</div> : null}
      {exportEvents.isError ? <ErrorState label={apiErrorMessage(exportEvents.error, "Não foi possível exportar os eventos.")} /> : null}
      {eventQuery.isError ? <ErrorState label="Não foi possível carregar os logs de eventos. Verifique sua sessão e tente novamente." /> : null}
      {!eventQuery.isLoading && !eventQuery.isError && rows.length === 0 ? (
        <EmptyState label="Nenhum evento encontrado." description="Ajuste os filtros ou aguarde novos eventos das catracas." />
      ) : null}

      {!eventQuery.isLoading && !eventQuery.isError && rows.length > 0 ? (
        <div className="space-y-4">
          <DataTable
            data={rows}
            getRowKey={(event) => event.id}
            columns={[
              { key: "time", header: "Horário", className: "min-w-[150px]", render: (event) => formatDate(event.occurredAt ?? event.eventTime) },
              { key: "person", header: "Pessoa", className: "min-w-[170px]", render: personLabel },
              { key: "cpf", header: "CPF", className: "min-w-[130px] whitespace-nowrap font-mono text-xs", render: (event) => formatCpf(event.personCpf) },
              { key: "phone", header: "Telefone", className: "min-w-[130px] whitespace-nowrap", render: (event) => event.personPhone ?? "Não informado" },
              { key: "email", header: "E-mail", className: "min-w-[190px]", render: (event) => event.personEmail ?? "Não informado" },
              { key: "invitedDay", header: "Dia", render: (event) => formatDateOnly(event.invitedDay) },
              { key: "lounge", header: "Camarote", render: (event) => event.invitedLounge ? displayAreaName(event.invitedLounge) : "Não informado" },
              { key: "device", header: "Catraca/controladora", className: "min-w-[180px]", render: (event) => event.deviceName ?? deviceById.get(event.deviceId)?.name ?? event.deviceId.slice(0, 8) },
              { key: "area", header: "Entrada/local", className: "min-w-[160px]", render: (event) => event.areaName ? displayAreaName(event.areaName) : (areaById.get(event.areaId)?.name ? displayAreaName(areaById.get(event.areaId)?.name) : event.areaId.slice(0, 8)) },
              { key: "result", header: "Resultado", className: "min-w-[140px]", render: (event) => (
                <div className="flex flex-wrap items-center gap-1">
                  <StatusBadge value={event.accessResult} />
                  {event.cooldownBlocked && (
                    <span className="inline-flex items-center rounded-full bg-amber-500/15 px-2 py-0.5 text-[10px] font-semibold text-amber-400 ring-1 ring-inset ring-amber-500/30" title={event.cooldownReason ?? "Bloqueado por intervalo mínimo"}>
                      Bloqueado 15s
                    </span>
                  )}
                </div>
              ) },
              { key: "recognition", header: "Reconhecimento", render: (event) => <StatusBadge value={event.recognitionStatus} /> },
              { key: "passage", header: "Passagem", render: (event) => <StatusBadge value={event.passageStatus} /> },
              { key: "manualReason", header: "Motivo manual", className: "min-w-[220px]", render: (event) => event.manualReason ?? "Não se aplica" }
            ]}
          />

          <Card>
            <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
              <div className="flex items-center gap-2 text-sm text-slate-400">
                <FileClock className="h-4 w-4 text-brand-wine" />
                Página {(pageData?.page ?? page) + 1} de {Math.max(pageData?.totalPages ?? 1, 1)} · {pageData?.totalElements ?? 0} registro(s)
              </div>
              <div className="flex gap-2">
                <Button variant="secondary" icon={ChevronLeft} disabled={page === 0 || eventQuery.isFetching} onClick={() => setPage((current) => Math.max(current - 1, 0))}>
                  Anterior
                </Button>
                <Button
                  variant="secondary"
                  icon={ChevronRight}
                  disabled={eventQuery.isFetching || !pageData || page + 1 >= pageData.totalPages}
                  onClick={() => setPage((current) => current + 1)}
                >
                  Próxima
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      ) : null}

      <Modal
        open={manualOpen}
        title="Registrar liberação manual"
        description="Use somente após validar a pessoa pelo fluxo operacional de contingência."
        onClose={() => {
          if (!manualRelease.isPending) {
            setManualOpen(false);
            setManualConfirmed(false);
          }
        }}
      >
        <form onSubmit={submitManualRelease} className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <Input label="Nome" required value={manualForm.personName} onChange={(event) => setManualForm((current) => ({ ...current, personName: event.target.value }))} />
            <Input label="CPF" value={manualForm.personCpf ?? ""} onChange={(event) => setManualForm((current) => ({ ...current, personCpf: formatCpfInput(event.target.value) }))} inputMode="numeric" />
            <Select label="Catraca/device" required value={manualForm.deviceId} onChange={(event) => setManualForm((current) => ({ ...current, deviceId: event.target.value }))}>
              <option value="">Selecione</option>
              {(devices.data ?? []).map((device) => (
                <option key={device.id} value={device.id}>{device.name}</option>
              ))}
            </Select>
            <Input label="Motivo" required value={manualForm.reason} onChange={(event) => setManualForm((current) => ({ ...current, reason: event.target.value }))} />
          </div>
          <Textarea label="Observação" value={manualForm.operatorObservation ?? ""} onChange={(event) => setManualForm((current) => ({ ...current, operatorObservation: event.target.value }))} />
          <label className="flex items-start gap-2 rounded-xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm font-medium text-amber-100">
            <input
              type="checkbox"
              checked={manualConfirmed}
              onChange={(event) => setManualConfirmed(event.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-white/15 bg-white/[0.055] accent-amber-500"
            />
            Confirmo que a pessoa foi validada pelo procedimento manual e que esta liberação deve ser auditada.
          </label>
          {manualRelease.isError ? (
            <ErrorState label="Não foi possível registrar a liberação manual. Verifique sua permissão e os dados informados." />
          ) : null}
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" disabled={manualRelease.isPending} onClick={() => setManualOpen(false)}>Cancelar</Button>
            <Button type="submit" icon={ShieldCheck} loading={manualRelease.isPending} disabled={!manualConfirmed}>
              Registrar liberação
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={cleanupOpen}
        title="Limpar eventos de teste"
        description="Esta ação remove eventos/logs operacionais de acesso. Não apaga visitantes, colaboradores, dispositivos, áreas ou configurações."
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
            Digite <span className="font-semibold">LIMPAR_EVENTOS</span> para confirmar a limpeza dos eventos de acesso.
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

function EnumSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return (
    <Select label={label} value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">Todos</option>
      {options.map((option) => (
        <option key={option} value={option}>{humanizeStatus(option)}</option>
      ))}
    </Select>
  );
}

function toApiFilters(filters: FilterForm, page: number): AccessEventFilters {
  return {
    ...filters,
    page,
    size: pageSize,
    startDate: toIso(filters.startDate),
    endDate: toIso(filters.endDate)
  };
}

function toExportFilters(filters: FilterForm): AccessEventFilters {
  return {
    ...filters,
    startDate: toIso(filters.startDate),
    endDate: toIso(filters.endDate)
  };
}

function toIso(value?: string) {
  if (!value) return undefined;
  return new Date(value).toISOString();
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString("pt-BR") : "Não informado";
}

function formatDateOnly(value?: string | null) {
  return value ? new Date(`${value}T00:00:00`).toLocaleDateString("pt-BR") : "Não informado";
}

function formatCpf(value?: string | null) {
  return formatCpfDisplay(value);
}

function personLabel(event: AccessEvent) {
  if (event.personName) return event.personName;
  if (event.rawCardName) return event.rawCardName;
  if (event.externalUserId) return `Usuário ${event.externalUserId}`;
  return "Usuário não identificado";
}
