"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, CalendarDays, CheckCircle2, Copy, Eye, Loader2, Mail, Plus, RefreshCw, RotateCcw, Search, Trash2, XCircle, type LucideIcon } from "lucide-react";
import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { Device, deviceService } from "@/services/deviceService";
import { guestService, Guest, GuestStatus, SyncStatus } from "@/services/guestService";
import { integrationService } from "@/services/integrationService";
import { useRealtime } from "@/src/hooks/useRealtime";
import { Badge } from "@/src/components/ui/Badge";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input, Select } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";

const statuses: Array<GuestStatus | "ALL"> = ["ALL", "PENDING_REGISTRATION", "COMPLETED", "EXPIRED", "CANCELLED"];

type CleanupForm = {
  cancelled: boolean;
  failed: boolean;
  oldPending: boolean;
  testRecords: boolean;
  allOld: boolean;
  olderThanDays: number;
};

type Toast = {
  tone: "success" | "error" | "info";
  message: string;
};

function localDateTime(daysFromNow = 0) {
  const date = new Date();
  date.setDate(date.getDate() + daysFromNow);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
}

export default function GuestsPage() {
  const queryClient = useQueryClient();
  const realtime = useRealtime();
  const guests = useQuery({ queryKey: ["guests"], queryFn: guestService.list });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const intelbrasStatus = useQuery({ queryKey: ["intelbras-status"], queryFn: integrationService.intelbrasStatus });
  const [open, setOpen] = useState(false);
  const [cleanupOpen, setCleanupOpen] = useState(false);
  const [details, setDetails] = useState<Guest | null>(null);
  const [cancelTarget, setCancelTarget] = useState<Guest | null>(null);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<GuestStatus | "ALL">("ALL");
  const [message, setMessage] = useState("");
  const [toast, setToast] = useState<Toast | null>(null);
  const [activeSyncId, setActiveSyncId] = useState<string | null>(null);
  const [cleanupForm, setCleanupForm] = useState<CleanupForm>({
    cancelled: true,
    failed: false,
    oldPending: false,
    testRecords: false,
    allOld: false,
    olderThanDays: 30
  });
  const [form, setForm] = useState({
    fullName: "",
    cpf: "",
    email: "",
    phone: "",
    company: "",
    visitReason: "",
    hostName: "",
    visitStart: localDateTime(),
    visitEnd: localDateTime(1)
  });

  const intelbrasDevices = useMemo(() => (devices.data ?? []).filter(isIntelbrasDevice), [devices.data]);
  const primaryIntelbrasDevice = intelbrasDevices[0];
  const syncEnabled = intelbrasStatus.data?.syncEnabled === true;
  const intelbrasMode = intelbrasStatus.data?.mode ?? "fake";

  const guestsWithRealtime = useMemo(() => {
    const latestSyncByGuest = new Map<string, { syncStatus: string; message?: string; occurredAt?: string }>();
    for (const event of realtime.integrationSync) {
      if (event.personType !== "GUEST" || latestSyncByGuest.has(event.personId)) continue;
      latestSyncByGuest.set(event.personId, event);
    }

    return (guests.data ?? []).map((guest) => {
      const sync = latestSyncByGuest.get(guest.id);
      if (!sync) return guest;
      return {
        ...guest,
        syncStatus: sync.syncStatus as SyncStatus,
        lastSyncAt: sync.syncStatus === "SYNCED" || sync.syncStatus === "SYNC_FAILED" ? sync.occurredAt ?? guest.lastSyncAt : guest.lastSyncAt,
        lastSyncError: sync.syncStatus === "SYNC_FAILED" ? sync.message ?? guest.lastSyncError : sync.syncStatus === "SYNCED" ? undefined : guest.lastSyncError
      };
    });
  }, [guests.data, realtime.integrationSync]);

  const selectedDetails = details ? guestsWithRealtime.find((guest) => guest.id === details.id) ?? details : null;

  const create = useMutation({
    mutationFn: () => guestService.create({ ...form, visitStart: new Date(form.visitStart).toISOString(), visitEnd: new Date(form.visitEnd).toISOString() }),
    onSuccess: (guest) => {
      setMessage(guest.emailDeliveryStatus === "SENT" ? "Visitante criado e e-mail enviado." : "Visitante criado. Link de convite gerado e e-mail não enviado neste ambiente.");
      setDetails(guest);
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o visitante."))
  });

  const cancel = useMutation({
    mutationFn: (id: string) => guestService.cancel(id),
    onSuccess: () => {
      setToast({ tone: "success", message: "Visitante cancelado." });
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    },
    onError: (error) => setToast({ tone: "error", message: apiErrorMessage(error, "Não foi possível cancelar o visitante.") })
  });

  const cleanup = useMutation({
    mutationFn: () => guestService.cleanup(cleanupPayload(cleanupForm)),
    onSuccess: (result) => {
      setToast({ tone: "success", message: `${result.removedCount} registros removidos com sucesso.` });
      setCleanupOpen(false);
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    },
    onError: (error) => setToast({ tone: "error", message: apiErrorMessage(error, "Não foi possível limpar a lista de visitantes.") })
  });

  const sync = useMutation({
    mutationFn: (id: string) => guestService.retryIntelbrasSync(id),
    onMutate: async (id) => {
      setActiveSyncId(id);
      setToast({ tone: "info", message: "Sincronização Intelbras enfileirada." });
      await queryClient.cancelQueries({ queryKey: ["guests"] });
      queryClient.setQueryData<Guest[]>(["guests"], (current) =>
        current?.map((guest) => guest.id === id ? { ...guest, syncStatus: "SYNCING", lastSyncError: undefined } : guest)
      );
    },
    onSuccess: () => {
      setToast({ tone: "success", message: "Pedido de sincronização enviado para o backend." });
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    },
    onError: (error) => setToast({ tone: "error", message: apiErrorMessage(error, "Não foi possível iniciar a sincronização Intelbras.") }),
    onSettled: () => setActiveSyncId(null)
  });

  useEffect(() => {
    if (!toast) return;
    const timeout = window.setTimeout(() => setToast(null), 4500);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  useEffect(() => {
    if (!realtime.integrationSync.some((event) => event.personType === "GUEST")) return;
    queryClient.invalidateQueries({ queryKey: ["guests"] });
  }, [queryClient, realtime.integrationSync.length]);

  const filteredGuests = useMemo(() => {
    const term = search.trim().toLowerCase();
    return guestsWithRealtime.filter((guest) => {
      const matchesStatus = status === "ALL" || guest.status === status;
      const matchesSearch = !term || [guest.fullName, guest.cpf, guest.email, guest.company, guest.hostName].some((value) => value?.toLowerCase().includes(term));
      return matchesStatus && matchesSearch;
    });
  }, [guestsWithRealtime, search, status]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  function inviteUrl(guest: Guest) {
    if (guest.inviteUrl) return guest.inviteUrl;
    if (!guest.inviteToken || typeof window === "undefined") return "";
    return `${window.location.origin}/guest-registration/${guest.inviteToken}`;
  }

  function emailStatusLabel(guest: Guest) {
    if (guest.emailDeliveryStatus === "SENT") return "E-mail enviado";
    if (guest.emailDeliveryStatus === "FAILED") return "Falha no e-mail";
    if (guest.emailDeliveryStatus === "SKIPPED") return "E-mail pulado";
    return "E-mail não informado";
  }

  function requestSync(guest: Guest) {
    const reason = syncDisabledReason(guest, syncEnabled, intelbrasStatus.isLoading, false);
    if (reason) {
      setToast({ tone: "error", message: reason });
      return;
    }
    sync.mutate(guest.id);
  }

  function confirmCancel() {
    if (!cancelTarget) return;
    cancel.mutate(cancelTarget.id, { onSettled: () => setCancelTarget(null) });
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Visitantes"
        title="Convidados"
        description="Convites, cadastro facial e sincronização operacional com Intelbras real."
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" icon={Trash2} onClick={() => setCleanupOpen(true)}>Limpar lista</Button>
            <Button icon={Plus} onClick={() => setOpen(true)}>Novo visitante</Button>
          </div>
        )}
      />

      {toast ? (
        <div role="status" className={`fixed right-5 top-5 z-[60] max-w-sm rounded-xl border px-4 py-3 text-sm font-semibold shadow-enterprise backdrop-blur ${toast.tone === "error" ? "border-red-300/20 bg-red-500/20 text-red-100" : toast.tone === "success" ? "border-emerald-300/20 bg-emerald-400/20 text-emerald-100" : "border-sky-300/20 bg-sky-400/20 text-sky-100"}`}>
          {toast.message}
        </div>
      ) : null}

      <Card className="mb-5">
        <CardContent className="grid gap-3 lg:grid-cols-[1fr_220px]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar por nome, CPF, empresa ou host" className="h-10 w-full rounded-xl border border-white/10 bg-white/[0.055] pl-10 pr-3 text-sm text-slate-100 outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]" />
          </div>
          <Select label="Status" value={status} onChange={(event) => setStatus(event.target.value as typeof status)}>
            {statuses.map((item) => <option key={item} value={item}>{item === "ALL" ? "Todos" : guestStatusLabel(item)}</option>)}
          </Select>
        </CardContent>
      </Card>

      {!intelbrasStatus.isLoading && !syncEnabled ? (
        <div className="mb-4 rounded-xl border border-amber-300/20 bg-amber-400/12 px-4 py-3 text-sm font-medium text-amber-100">
          Modo de desenvolvimento ativo. A sincronização manual com Intelbras real fica desabilitada até `APP_INTELBRAS_MODE=real`.
        </div>
      ) : null}

      {message ? <div className="mb-4 rounded-xl border border-white/10 bg-white/[0.055] px-4 py-3 text-sm font-medium text-slate-300 shadow-sm">{message}</div> : null}
      {guests.isLoading ? <GuestsTableSkeleton /> : null}
      {guests.isError ? <ErrorState label="Não foi possível carregar visitantes." /> : null}
      {!guests.isLoading && !guests.isError && guests.data?.length === 0 ? <EmptyState label="Nenhum visitante cadastrado." description="Crie um convite para iniciar o cadastro facial." /> : null}
      {!guests.isLoading && !guests.isError && (guests.data?.length ?? 0) > 0 && filteredGuests.length === 0 ? <EmptyState label="Nenhum visitante encontrado." description="Ajuste os filtros ou limpe a busca para ver outros registros." /> : null}
      {!guests.isLoading && !guests.isError && filteredGuests.length > 0 ? (
        <DataTable
          data={filteredGuests}
          getRowKey={(guest) => guest.id}
          columns={[
            { key: "name", header: "Visitante", className: "min-w-[180px] font-semibold text-slate-100", render: (guest) => guest.fullName },
            { key: "company", header: "Empresa", render: (guest) => guest.company ?? "Não informada" },
            { key: "host", header: "Responsável", render: (guest) => guest.hostName },
            { key: "visit", header: "Visita", className: "min-w-[190px]", render: (guest) => <span className="inline-flex items-center gap-2"><CalendarDays className="h-4 w-4 text-slate-400" />{new Date(guest.visitStart).toLocaleString("pt-BR")}</span> },
            { key: "status", header: "Status", render: (guest) => <StatusBadge value={guest.status} /> },
            { key: "integration", header: "Integração", className: "min-w-[180px]", render: (guest) => <SyncBadge guest={guest} /> },
            {
              key: "actions",
              header: "Ações",
              headerClassName: "min-w-[460px]",
              className: "min-w-[460px]",
              render: (guest) => {
                const syncReason = syncDisabledReason(guest, syncEnabled, intelbrasStatus.isLoading, false);
                const retryReason = syncDisabledReason(guest, syncEnabled, intelbrasStatus.isLoading, true);
                const isSyncing = activeSyncId === guest.id && sync.isPending;
                const syncButtonLabel = guest.syncStatus === "SYNCED" ? "Atualizar na Intelbras" : "Sincronizar";
                const syncButtonTitle = guest.syncStatus === "SYNCED" ? "Atualizar cadastro e face na Intelbras" : "Sincronizar visitante com Intelbras";
                return (
                  <div className="flex min-w-[430px] flex-wrap items-center gap-2">
                    <ActionButton title="Abrir detalhes operacionais do visitante" ariaLabel={`Abrir detalhes de ${guest.fullName}`} icon={Eye} onClick={() => setDetails(guest)}>Detalhes</ActionButton>
                    <ActionButton title={syncReason || syncButtonTitle} ariaLabel={`${syncButtonLabel} ${guest.fullName}`} icon={RefreshCw} loading={isSyncing} disabled={Boolean(syncReason) || isSyncing} onClick={() => requestSync(guest)}>{syncButtonLabel}</ActionButton>
                    <ActionButton title={retryReason || "Tentar sincronização novamente"} ariaLabel={`Tentar novamente sincronização Intelbras para ${guest.fullName}`} icon={RotateCcw} loading={isSyncing} disabled={Boolean(retryReason) || isSyncing} onClick={() => requestSync(guest)}>Tentar novamente</ActionButton>
                    <ActionButton title={guest.status === "CANCELLED" ? "Visitante já cancelado" : "Cancelar visitante"} ariaLabel={`Cancelar ${guest.fullName}`} icon={XCircle} disabled={guest.status === "CANCELLED" || cancel.isPending} onClick={() => setCancelTarget(guest)}>Cancelar</ActionButton>
                  </div>
                );
              }
            }
          ]}
        />
      ) : null}

      <Modal title="Novo visitante" description="Gere um convite para cadastro facial público por token." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-4">
          <Input label="Nome" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="CPF" value={form.cpf} onChange={(event) => setForm({ ...form, cpf: event.target.value })} required />
            <Input label="E-mail" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Telefone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
            <Input label="Empresa" value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Responsável" value={form.hostName} onChange={(event) => setForm({ ...form, hostName: event.target.value })} required />
            <Input label="Motivo da visita" value={form.visitReason} onChange={(event) => setForm({ ...form, visitReason: event.target.value })} required />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Início" type="datetime-local" value={form.visitStart} onChange={(event) => setForm({ ...form, visitStart: event.target.value })} required />
            <Input label="Fim" type="datetime-local" value={form.visitEnd} onChange={(event) => setForm({ ...form, visitEnd: event.target.value })} required />
          </div>
          {create.isError ? <ErrorState label={message || "Não foi possível criar visitante."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Criar convite</Button>
          </div>
        </form>
      </Modal>

      <Modal title="Detalhes do visitante" open={!!selectedDetails} onClose={() => setDetails(null)}>
        {selectedDetails ? (
          <div className="space-y-4">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-slate-50">{selectedDetails.fullName}</h2>
                <p className="text-sm text-slate-500">{selectedDetails.company ?? "Sem empresa"} · {selectedDetails.hostName}</p>
              </div>
              <StatusBadge value={selectedDetails.status} />
            </div>

            <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Mail className="h-4 w-4 text-slate-500" />
                  <p className="text-sm font-semibold text-slate-100">{emailStatusLabel(selectedDetails)}</p>
                </div>
                {selectedDetails.emailDeliveryStatus ? <StatusBadge value={selectedDetails.emailDeliveryStatus} /> : null}
              </div>
              {selectedDetails.emailDeliveryMessage ? <p className="mt-1 text-xs text-slate-500">{selectedDetails.emailDeliveryMessage}</p> : null}
            </div>

            <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
              <div className="mb-3 flex items-center justify-between gap-3">
                <p className="text-sm font-semibold text-slate-100">Integração Intelbras</p>
                <SyncBadge guest={selectedDetails} />
              </div>
              <div className="grid gap-3 text-sm sm:grid-cols-2">
                <DetailItem label="Dispositivo vinculado" value={linkedDeviceLabel(intelbrasDevices)} />
                <div>
                  <p className="font-semibold text-slate-300">Status do dispositivo</p>
                  <div className="mt-1">{primaryIntelbrasDevice ? <StatusBadge value={primaryIntelbrasDevice.status} /> : <span className="text-slate-500">Não configurado</span>}</div>
                </div>
                <DetailItem label="Status Intelbras" value={syncLabel(selectedDetails.syncStatus)} />
                <DetailItem label="Última sincronização" value={formatDate(selectedDetails.lastSyncAt)} />
                <DetailItem label="Erro da última sincronização" value={selectedDetails.lastSyncError ?? "Sem erro registrado"} danger={Boolean(selectedDetails.lastSyncError)} />
                <DetailItem label="Face enviada" value={selectedDetails.facePhotoUrl ? "sim" : "não"} />
                <DetailItem label="Usuário Intelbras criado" value={intelbrasUserCreated(selectedDetails)} />
                <DetailItem label="Modo Intelbras" value={syncEnabled ? "real" : `desenvolvimento (${intelbrasMode})`} />
              </div>
            </div>

            <div className="grid gap-3 text-sm sm:grid-cols-2">
              <p><span className="font-semibold text-slate-300">CPF:</span> {selectedDetails.cpf}</p>
              <p><span className="font-semibold text-slate-300">E-mail:</span> {selectedDetails.email ?? "Não informado"}</p>
              <p><span className="font-semibold text-slate-300">Início:</span> {new Date(selectedDetails.visitStart).toLocaleString("pt-BR")}</p>
              <p><span className="font-semibold text-slate-300">Fim:</span> {new Date(selectedDetails.visitEnd).toLocaleString("pt-BR")}</p>
            </div>

            {selectedDetails.inviteToken ? (
              <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
                <p className="mb-2 text-sm font-semibold text-slate-100">Link de convite</p>
                <div className="flex gap-2">
                  <input readOnly value={inviteUrl(selectedDetails)} className="h-10 flex-1 rounded-xl border border-white/10 bg-white/[0.055] px-3 text-sm text-slate-100" />
                  <Button icon={Copy} onClick={() => navigator.clipboard.writeText(inviteUrl(selectedDetails))}>Copiar</Button>
                </div>
                {selectedDetails.inviteExpiresAt ? <p className="mt-2 text-xs text-slate-500">Expira em {new Date(selectedDetails.inviteExpiresAt).toLocaleString("pt-BR")}</p> : null}
              </div>
            ) : null}
          </div>
        ) : null}
      </Modal>

      <Modal title="Limpar lista de visitantes" description="Remova registros antigos com critérios administrativos." open={cleanupOpen} onClose={() => setCleanupOpen(false)}>
        <form onSubmit={(event) => { event.preventDefault(); cleanup.mutate(); }} className="space-y-5">
          <div className="grid gap-3 sm:grid-cols-2">
            <CleanupOption label="Apenas cancelados" checked={cleanupForm.cancelled} onChange={(checked) => setCleanupForm({ ...cleanupForm, cancelled: checked })} />
            <CleanupOption label="Apenas falhos" checked={cleanupForm.failed} onChange={(checked) => setCleanupForm({ ...cleanupForm, failed: checked })} />
            <CleanupOption label="Apenas pendentes antigos" checked={cleanupForm.oldPending} onChange={(checked) => setCleanupForm({ ...cleanupForm, oldPending: checked })} />
            <CleanupOption label="Apenas registros de teste/mock" checked={cleanupForm.testRecords} onChange={(checked) => setCleanupForm({ ...cleanupForm, testRecords: checked })} />
            <CleanupOption label="Todos os registros antigos" checked={cleanupForm.allOld} onChange={(checked) => setCleanupForm({ ...cleanupForm, allOld: checked })} />
          </div>
          <Input label="Mais antigos que X dias" type="number" min={0} value={cleanupForm.olderThanDays} onChange={(event) => setCleanupForm({ ...cleanupForm, olderThanDays: Number(event.target.value) })} />
          <div className="rounded-xl border border-amber-300/20 bg-amber-400/10 px-4 py-3 text-sm text-amber-100">
            A limpeza remove convites vinculados aos visitantes selecionados e registra auditoria administrativa.
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" onClick={() => setCleanupOpen(false)}>Voltar</Button>
            <Button variant="danger" icon={Trash2} loading={cleanup.isPending} type="submit">Confirmar limpeza</Button>
          </div>
        </form>
      </Modal>

      <Modal title="Cancelar visitante" description="Confirme antes de cancelar este convite." open={!!cancelTarget} onClose={() => setCancelTarget(null)}>
        <div className="space-y-4">
          <p className="text-sm text-slate-300">
            Deseja cancelar o visitante <span className="font-semibold text-slate-50">{cancelTarget?.fullName}</span>?
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setCancelTarget(null)}>Voltar</Button>
            <Button variant="danger" icon={XCircle} loading={cancel.isPending} onClick={confirmCancel}>Cancelar visitante</Button>
          </div>
        </div>
      </Modal>
    </AdminShell>
  );
}

function ActionButton({
  title,
  ariaLabel,
  icon,
  loading,
  disabled,
  onClick,
  children
}: {
  title: string;
  ariaLabel: string;
  icon: LucideIcon;
  loading?: boolean;
  disabled?: boolean;
  onClick: () => void;
  children: string;
}) {
  return (
    <span title={title} className="inline-flex">
      <Button
        aria-label={ariaLabel}
        variant="secondary"
        icon={icon}
        loading={loading}
        disabled={disabled}
        className="h-10 min-w-fit whitespace-nowrap px-3"
        onClick={onClick}
      >
        {children}
      </Button>
    </span>
  );
}

function SyncBadge({ guest }: { guest: Guest }) {
  const status = guest.syncStatus ?? "NOT_REQUIRED";
  const title = status === "SYNC_FAILED" && guest.lastSyncError ? guest.lastSyncError : syncLabel(status);
  const Icon = syncIcon(status);
  return (
    <Badge tone={syncTone(status)} title={title} className="gap-1.5">
      <Icon className={`h-3.5 w-3.5 ${status === "SYNCING" ? "animate-spin" : ""}`} />
      {syncLabel(status)}
    </Badge>
  );
}

function DetailItem({ label, value, danger = false }: { label: string; value: string; danger?: boolean }) {
  return (
    <div>
      <p className="font-semibold text-slate-300">{label}</p>
      <p className={`mt-1 ${danger ? "text-red-200" : "text-slate-500"}`}>{value}</p>
    </div>
  );
}

function CleanupOption({ label, checked, onChange }: { label: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return (
    <label className="flex min-h-12 cursor-pointer items-center gap-3 rounded-xl border border-white/10 bg-white/[0.045] px-3 py-2 text-sm font-semibold text-slate-200 transition hover:border-white/20 hover:bg-white/[0.075]">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        className="h-4 w-4 accent-brand-wine"
      />
      {label}
    </label>
  );
}

function GuestsTableSkeleton() {
  return (
    <Card className="overflow-hidden">
      <div className="space-y-0">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="grid grid-cols-[1.2fr_0.8fr_0.8fr_1fr_0.7fr_0.8fr_1.6fr] gap-4 border-b border-white/10 px-5 py-4 last:border-b-0">
            {Array.from({ length: 7 }).map((__, cell) => (
              <div key={cell} className="h-4 animate-pulse rounded-full bg-white/[0.08]" />
            ))}
          </div>
        ))}
      </div>
    </Card>
  );
}

function cleanupPayload(form: CleanupForm) {
  const status: GuestStatus[] = [];
  const integrationStatus: SyncStatus[] = [];
  if (form.cancelled) status.push("CANCELLED");
  if (form.oldPending) status.push("PENDING_REGISTRATION");
  if (form.failed) integrationStatus.push("SYNC_FAILED");
  return {
    status: form.allOld ? [] : Array.from(new Set(status)),
    integrationStatus: form.allOld ? [] : integrationStatus,
    olderThanDays: Math.max(0, form.olderThanDays || 0),
    onlyTestRecords: form.testRecords
  };
}

function syncDisabledReason(guest: Guest, syncEnabled: boolean, modeLoading: boolean, retry: boolean) {
  if (modeLoading) return "Verificando modo Intelbras.";
  if (!syncEnabled) return "Modo de desenvolvimento ativo";
  if (guest.status !== "COMPLETED") return "Visitante precisa estar completo para sincronizar.";
  if (!guest.facePhotoUrl) return "Visitante precisa enviar foto facial antes da sincronização.";
  if (guest.syncStatus === "SYNCING") return "Sincronização em andamento.";
  if (retry && guest.syncStatus !== "SYNC_FAILED") return "Tentar novamente fica disponível apenas quando a sincronização falhar.";
  return "";
}

function isIntelbrasDevice(device: Device) {
  return [device.model, device.name].some((value) => value?.toLowerCase().includes("intelbras"));
}

function linkedDeviceLabel(devices: Device[]) {
  if (devices.length === 0) return "Nenhum dispositivo Intelbras configurado";
  const primary = devices[0];
  const label = primary.model || primary.name;
  return devices.length === 1 ? label : `${label} +${devices.length - 1}`;
}

function intelbrasUserCreated(guest: Guest) {
  if (guest.syncStatus === "SYNCED") return "sim";
  if (guest.syncStatus === "SYNCING") return "em andamento";
  if (guest.syncStatus === "SYNC_FAILED") return "não";
  return "pendente";
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString("pt-BR") : "não informado";
}

function guestStatusLabel(status: GuestStatus) {
  const labels: Record<GuestStatus, string> = {
    INVITED: "Convidado",
    PENDING_REGISTRATION: "Cadastro pendente",
    COMPLETED: "Completo",
    EXPIRED: "Expirado",
    CANCELLED: "Cancelado"
  };
  return labels[status];
}

function syncLabel(status?: string) {
  const labels: Record<string, string> = {
    PENDING_SYNC: "Pendente",
    SYNCING: "Sincronizando",
    SYNCED: "Sincronizado",
    SYNC_FAILED: "Falhou",
    NOT_REQUIRED: "Não requer"
  };
  return labels[status ?? "NOT_REQUIRED"] ?? status ?? "Não requer";
}

function syncTone(status?: string): "slate" | "red" | "green" | "amber" | "blue" {
  if (status === "SYNCED") return "green";
  if (status === "SYNCING") return "blue";
  if (status === "SYNC_FAILED") return "red";
  if (status === "PENDING_SYNC") return "amber";
  return "slate";
}

function syncIcon(status?: string): LucideIcon {
  if (status === "SYNCED") return CheckCircle2;
  if (status === "SYNCING") return Loader2;
  if (status === "SYNC_FAILED") return AlertCircle;
  if (status === "PENDING_SYNC") return RefreshCw;
  return CheckCircle2;
}
