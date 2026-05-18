"use client";

import { FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarDays, Copy, Mail, Plus, RefreshCw, Search, XCircle } from "lucide-react";
import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { guestService, Guest, GuestStatus } from "@/services/guestService";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input, Select, Textarea } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";

const statuses: Array<GuestStatus | "ALL"> = ["ALL", "PENDING_REGISTRATION", "COMPLETED", "EXPIRED", "CANCELLED"];

function localDateTime(daysFromNow = 0) {
  const date = new Date();
  date.setDate(date.getDate() + daysFromNow);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
}

export default function GuestsPage() {
  const queryClient = useQueryClient();
  const guests = useQuery({ queryKey: ["guests"], queryFn: guestService.list });
  const [open, setOpen] = useState(false);
  const [details, setDetails] = useState<Guest | null>(null);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<GuestStatus | "ALL">("ALL");
  const [message, setMessage] = useState("");
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
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["guests"] })
  });

  const resend = useMutation({
    mutationFn: (id: string) => guestService.resendInvite(id),
    onSuccess: (guest) => {
      setDetails(guest);
      setMessage(guest.emailDeliveryStatus === "SENT" ? "Convite reenviado por e-mail." : "Novo link gerado. E-mail não enviado ou falhou; use copiar link.");
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    }
  });

  const filteredGuests = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (guests.data ?? []).filter((guest) => {
      const matchesStatus = status === "ALL" || guest.status === status;
      const matchesSearch = !term || [guest.fullName, guest.cpf, guest.email, guest.company, guest.hostName].some((value) => value?.toLowerCase().includes(term));
      return matchesStatus && matchesSearch;
    });
  }, [guests.data, search, status]);

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

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Visitantes"
        title="Convidados"
        description="Convites, cadastro facial e acompanhamento de visitantes antes da integracao Intelbras real."
        actions={<Button icon={Plus} onClick={() => setOpen(true)}>Novo visitante</Button>}
      />

      <Card className="mb-5">
        <CardContent className="grid gap-3 lg:grid-cols-[1fr_220px]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar por nome, CPF, empresa ou host" className="h-10 w-full rounded-lg border border-slate-300 bg-white pl-10 pr-3 text-sm outline-none focus:border-sport-red" />
          </div>
          <Select label="Status" value={status} onChange={(event) => setStatus(event.target.value as typeof status)}>
            {statuses.map((item) => <option key={item} value={item}>{item === "ALL" ? "Todos" : item}</option>)}
          </Select>
        </CardContent>
      </Card>

      {message ? <div className="mb-4 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm font-medium text-slate-700 shadow-sm">{message}</div> : null}
      {guests.isLoading ? <LoadingState label="Carregando visitantes..." /> : null}
      {guests.isError ? <ErrorState label="Não foi possível carregar visitantes." /> : null}
      {!guests.isLoading && !guests.isError && guests.data?.length === 0 ? <EmptyState label="Nenhum visitante cadastrado." description="Crie um convite para iniciar o cadastro facial." /> : null}
      {!guests.isLoading && !guests.isError && filteredGuests.length > 0 ? (
        <DataTable
          data={filteredGuests}
          getRowKey={(guest) => guest.id}
          columns={[
            { key: "name", header: "Visitante", className: "px-4 py-3 font-semibold text-slate-950", render: (guest) => guest.fullName },
            { key: "company", header: "Empresa", render: (guest) => guest.company ?? "Nao informada" },
            { key: "host", header: "Responsavel", render: (guest) => guest.hostName },
            { key: "visit", header: "Visita", render: (guest) => <span className="inline-flex items-center gap-2"><CalendarDays className="h-4 w-4 text-slate-400" />{new Date(guest.visitStart).toLocaleString("pt-BR")}</span> },
            { key: "status", header: "Status", render: (guest) => <StatusBadge value={guest.status} /> },
            {
              key: "actions",
              header: "Acoes",
              render: (guest) => (
                <div className="flex items-center gap-1">
                  <Button variant="ghost" onClick={() => setDetails(guest)}>Detalhes</Button>
                  <Button aria-label="Reenviar convite" variant="ghost" icon={RefreshCw} className="h-9 w-9 px-0" onClick={() => resend.mutate(guest.id)} disabled={guest.status === "COMPLETED" || resend.isPending} />
                  <Button aria-label="Cancelar" variant="ghost" icon={XCircle} className="h-9 w-9 px-0" onClick={() => cancel.mutate(guest.id)} disabled={guest.status === "CANCELLED"} />
                </div>
              )
            }
          ]}
        />
      ) : null}

      <Modal title="Novo visitante" description="Gere um convite para cadastro facial publico por token." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-4">
          <Input label="Nome" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="CPF" value={form.cpf} onChange={(event) => setForm({ ...form, cpf: event.target.value })} required />
            <Input label="Email" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Telefone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
            <Input label="Empresa" value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Responsavel" value={form.hostName} onChange={(event) => setForm({ ...form, hostName: event.target.value })} required />
            <Input label="Motivo da visita" value={form.visitReason} onChange={(event) => setForm({ ...form, visitReason: event.target.value })} required />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Inicio" type="datetime-local" value={form.visitStart} onChange={(event) => setForm({ ...form, visitStart: event.target.value })} required />
            <Input label="Fim" type="datetime-local" value={form.visitEnd} onChange={(event) => setForm({ ...form, visitEnd: event.target.value })} required />
          </div>
          {create.isError ? <ErrorState label={message || "Não foi possível criar visitante."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Criar convite</Button>
          </div>
        </form>
      </Modal>

      <Modal title="Detalhes do visitante" open={!!details} onClose={() => setDetails(null)}>
        {details ? (
          <div className="space-y-4">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-slate-950">{details.fullName}</h2>
                <p className="text-sm text-slate-500">{details.company ?? "Sem empresa"} · {details.hostName}</p>
              </div>
              <StatusBadge value={details.status} />
            </div>
            <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Mail className="h-4 w-4 text-slate-500" />
                  <p className="text-sm font-semibold text-slate-900">{emailStatusLabel(details)}</p>
                </div>
                {details.emailDeliveryStatus ? <StatusBadge value={details.emailDeliveryStatus} /> : null}
              </div>
              {details.emailDeliveryMessage ? <p className="mt-1 text-xs text-slate-500">{details.emailDeliveryMessage}</p> : null}
            </div>
            <div className="grid gap-3 text-sm sm:grid-cols-2">
              <p><span className="font-semibold text-slate-700">CPF:</span> {details.cpf}</p>
              <p><span className="font-semibold text-slate-700">Email:</span> {details.email ?? "Nao informado"}</p>
              <p><span className="font-semibold text-slate-700">Inicio:</span> {new Date(details.visitStart).toLocaleString("pt-BR")}</p>
              <p><span className="font-semibold text-slate-700">Fim:</span> {new Date(details.visitEnd).toLocaleString("pt-BR")}</p>
            </div>
            {details.inviteToken ? (
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                <p className="mb-2 text-sm font-semibold text-slate-900">Link de convite</p>
                <div className="flex gap-2">
                  <input readOnly value={inviteUrl(details)} className="h-10 flex-1 rounded-lg border border-slate-300 bg-white px-3 text-sm" />
                  <Button icon={Copy} onClick={() => navigator.clipboard.writeText(inviteUrl(details))}>Copiar</Button>
                </div>
                {details.inviteExpiresAt ? <p className="mt-2 text-xs text-slate-500">Expira em {new Date(details.inviteExpiresAt).toLocaleString("pt-BR")}</p> : null}
              </div>
            ) : null}
          </div>
        ) : null}
      </Modal>
    </AdminShell>
  );
}
