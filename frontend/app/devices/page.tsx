"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input, Select } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { areaService } from "@/services/areaService";
import { deviceService } from "@/services/deviceService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cpu, Hash, KeyRound, MapPin, Plus, Router, Sparkles, Wifi } from "lucide-react";
import { FormEvent, useState } from "react";

type OperationType = "ENTRY" | "EXIT" | "ENTRY_EXIT";
type DeviceStatus = "ONLINE" | "OFFLINE" | "MAINTENANCE" | "UNKNOWN";

type DeviceForm = {
  name: string;
  model: string;
  serialNumber: string;
  ipAddress: string;
  httpPort: string;
  intelbrasUsername: string;
  intelbrasPassword: string;
  location: string;
  operationType: OperationType;
  status: DeviceStatus;
  areaId: string;
};

const initialForm: DeviceForm = {
  name: "",
  model: "",
  serialNumber: "",
  ipAddress: "",
  httpPort: "80",
  intelbrasUsername: "",
  intelbrasPassword: "",
  location: "",
  operationType: "ENTRY_EXIT",
  status: "UNKNOWN",
  areaId: ""
};

export default function DevicesPage() {
  const queryClient = useQueryClient();
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [form, setForm] = useState<DeviceForm>(initialForm);
  const [formError, setFormError] = useState("");
  const [message, setMessage] = useState("");
  const [open, setOpen] = useState(false);

  const create = useMutation({
    mutationFn: () => deviceService.create({
      name: form.name.trim(),
      model: form.model.trim(),
      serialNumber: optionalValue(form.serialNumber),
      ipAddress: form.ipAddress.trim(),
      httpPort: Number(form.httpPort),
      intelbrasUsername: optionalValue(form.intelbrasUsername),
      intelbrasPassword: optionalValue(form.intelbrasPassword),
      location: optionalValue(form.location),
      operationType: form.operationType,
      status: form.status,
      areaId: form.areaId
    }),
    onSuccess: () => {
      setForm(initialForm);
      setFormError("");
      setMessage("Dispositivo criado com sucesso.");
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o dispositivo."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateForm(form);
    if (validation) {
      setFormError(validation);
      return;
    }
    setFormError("");
    create.mutate();
  }

  function updateForm<K extends keyof DeviceForm>(key: K, value: DeviceForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function applyIntelbrasPreset() {
    setForm((current) => ({
      ...current,
      model: "Intelbras SS 5531 MF W",
      httpPort: "80",
      operationType: "ENTRY_EXIT",
      status: "ONLINE"
    }));
  }

  function closeModal() {
    setOpen(false);
    setFormError("");
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Infraestrutura"
        title="Dispositivos"
        description="Catracas, controladoras e pontos de acesso vinculados as areas fisicas."
        actions={<Button icon={Plus} onClick={() => setOpen(true)}>Novo dispositivo</Button>}
      />
      {message ? <div className="mb-4 rounded-xl border border-white/10 bg-white/[0.055] px-4 py-3 text-sm font-medium text-slate-300 shadow-sm">{message}</div> : null}
      {devices.isLoading ? <LoadingState label="Carregando dispositivos..." /> : null}
      {devices.isError ? <ErrorState label="Não foi possível carregar os dispositivos." /> : null}
      {!devices.isLoading && !devices.isError && devices.data?.length === 0 ? (
        <EmptyState label="Nenhum dispositivo cadastrado ainda." description="Cadastre um dispositivo para acompanhar sua situacao operacional." />
      ) : null}
      {!devices.isLoading && !devices.isError && devices.data && devices.data.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {devices.data?.map((device) => (
            <Card key={device.id} className={device.status === "OFFLINE" || device.status === "UNKNOWN" ? "border-amber-300/25" : undefined}>
              <CardContent>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-white/10 bg-white/[0.06] text-slate-300">
                      <Router className="h-5 w-5" />
                    </div>
                    <div>
                      <p className="font-semibold text-slate-50">{device.name}</p>
                      <p className="mt-1 text-sm text-slate-500">{device.model ?? "Modelo nao informado"}</p>
                    </div>
                  </div>
                  <StatusBadge value={device.status} />
                </div>
                <div className="mt-5 grid gap-3 text-sm text-slate-400">
                  <p className="flex items-center gap-2"><Wifi className="h-4 w-4 text-slate-400" /> {formatAddress(device.ipAddress, device.httpPort)}</p>
                  <p className="flex items-center gap-2"><Hash className="h-4 w-4 text-slate-400" /> Serial: {device.serialNumber ?? "nao informado"}</p>
                  <p className="flex items-center gap-2"><MapPin className="h-4 w-4 text-slate-400" /> {device.areaName} · {device.location ?? "Sem local"}</p>
                  <p className="flex items-center gap-2"><KeyRound className="h-4 w-4 text-slate-400" /> Credenciais configuradas: {hasIntelbrasCredentials(device) ? "sim" : "nao"}</p>
                  <p className="flex items-center gap-2"><Cpu className="h-4 w-4 text-slate-400" /> Ultimo heartbeat: {formatDate(device.lastHeartbeatAt)}</p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      ) : null}
      <Modal title="Novo dispositivo" description="Cadastre controladoras reais com rede, operacao e credenciais Intelbras." open={open} onClose={closeModal}>
        <form onSubmit={submit} className="grid gap-5">
          <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="text-sm font-semibold text-slate-100">Dados básicos</p>
                <p className="mt-1 text-xs text-slate-500">Serial recomendado para auditoria e suporte técnico.</p>
              </div>
              <Button type="button" variant="secondary" icon={Sparkles} className="h-auto min-h-10 whitespace-normal py-2 text-left" onClick={applyIntelbrasPreset}>Preencher modelo Intelbras SS 5531</Button>
            </div>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Input label="Nome" value={form.name} onChange={(event) => updateForm("name", event.target.value)} required />
              <Input label="Modelo" value={form.model} onChange={(event) => updateForm("model", event.target.value)} required />
              <Input label="Número de série" value={form.serialNumber} onChange={(event) => updateForm("serialNumber", event.target.value)} placeholder="DRWL3903457HU" />
              <Select label="Area" value={form.areaId} onChange={(event) => updateForm("areaId", event.target.value)} required>
                <option value="">Selecione</option>
                {areas.data?.map((area) => <option key={area.id} value={area.id}>{area.name}</option>)}
              </Select>
            </div>
          </div>

          <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
            <p className="text-sm font-semibold text-slate-100">Rede</p>
            <div className="mt-4 grid gap-4 sm:grid-cols-[1fr_160px]">
              <Input label="IP" value={form.ipAddress} onChange={(event) => updateForm("ipAddress", event.target.value)} placeholder="192.168.15.5" required />
              <Input label="Porta HTTP" type="number" min={1} max={65535} value={form.httpPort} onChange={(event) => updateForm("httpPort", event.target.value)} required />
            </div>
            <div className="mt-4">
              <Input label="Localização" value={form.location} onChange={(event) => updateForm("location", event.target.value)} placeholder="Portaria principal" />
            </div>
          </div>

          <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
            <p className="text-sm font-semibold text-slate-100">Credenciais Intelbras</p>
            <p className="mt-1 text-xs text-slate-500">As credenciais são usadas somente pelo backend para comunicação com a controladora.</p>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Input label="Usuário Intelbras" value={form.intelbrasUsername} onChange={(event) => updateForm("intelbrasUsername", event.target.value)} autoComplete="username" />
              <Input label="Senha Intelbras" type="password" value={form.intelbrasPassword} onChange={(event) => updateForm("intelbrasPassword", event.target.value)} autoComplete="new-password" />
            </div>
          </div>

          <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
            <p className="text-sm font-semibold text-slate-100">Operação</p>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Select label="Tipo de operação" value={form.operationType} onChange={(event) => updateForm("operationType", event.target.value as OperationType)} required>
                <option value="ENTRY">Entrada</option>
                <option value="EXIT">Saída</option>
                <option value="ENTRY_EXIT">Entrada/Saída</option>
              </Select>
              <Select label="Status" value={form.status} onChange={(event) => updateForm("status", event.target.value as DeviceStatus)} required>
                <option value="ONLINE">Online</option>
                <option value="OFFLINE">Offline</option>
                <option value="MAINTENANCE">Manutenção</option>
                <option value="UNKNOWN">Desconhecido</option>
              </Select>
            </div>
          </div>

          {formError ? <ErrorState label={formError} /> : null}
          {create.isError ? <ErrorState label={message || "Não foi possível criar o dispositivo."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={closeModal}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Cadastrar</Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}

function optionalValue(value: string) {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

function validateForm(form: DeviceForm) {
  const port = Number(form.httpPort);
  const intelbrasModel = form.model.toLowerCase().includes("intelbras");

  if (!form.name.trim()) return "Informe o nome do dispositivo.";
  if (!form.model.trim()) return "Informe o modelo do dispositivo.";
  if (!form.ipAddress.trim()) return "Informe o IP do dispositivo.";
  if (!form.areaId) return "Selecione a area do dispositivo.";
  if (!Number.isInteger(port) || port < 1 || port > 65535) return "Informe uma porta HTTP entre 1 e 65535.";
  if (intelbrasModel && !form.intelbrasUsername.trim()) return "Informe o usuário Intelbras para este modelo.";
  if (intelbrasModel && !form.intelbrasPassword.trim()) return "Informe a senha Intelbras para este modelo.";
  return "";
}

function formatAddress(ipAddress: string, httpPort?: number) {
  return httpPort ? `${ipAddress}:${httpPort}` : ipAddress;
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString("pt-BR") : "nao informado";
}

function hasIntelbrasCredentials(device: { intelbrasUsername?: string; intelbrasPasswordConfigured?: boolean }) {
  return Boolean(device.intelbrasUsername || device.intelbrasPasswordConfigured);
}
