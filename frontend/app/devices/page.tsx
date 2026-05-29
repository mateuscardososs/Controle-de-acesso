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
import { authService } from "@/services/authService";
import { deviceService, type Device } from "@/services/deviceService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Cpu, Hash, KeyRound, MapPin, Pencil, Plus, Router, Sparkles, Trash2, Wifi, Zap } from "lucide-react";
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

function deviceToForm(device: Device): DeviceForm {
  return {
    name: device.name,
    model: device.model ?? "",
    serialNumber: device.serialNumber ?? "",
    ipAddress: device.ipAddress,
    httpPort: String(device.httpPort ?? 80),
    intelbrasUsername: device.intelbrasUsername ?? "",
    intelbrasPassword: "",
    location: device.location ?? "",
    operationType: (device.operationType as OperationType) ?? "ENTRY_EXIT",
    status: (device.status as DeviceStatus) ?? "UNKNOWN",
    areaId: device.areaId ?? ""
  };
}

export default function DevicesPage() {
  const queryClient = useQueryClient();
  const user = useQuery({ queryKey: ["me"], queryFn: authService.me, retry: false });
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const canRemoveDevices = user.data?.role === "ADMIN";

  const [form, setForm] = useState<DeviceForm>(initialForm);
  const [formError, setFormError] = useState("");
  const [message, setMessage] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [editDevice, setEditDevice] = useState<Device | null>(null);
  const [deleteDevice, setDeleteDevice] = useState<Device | null>(null);
  const [pingStates, setPingStates] = useState<Record<string, "pending" | "success" | "error">>({});

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
      setCreateOpen(false);
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o dispositivo."))
  });

  const update = useMutation({
    mutationFn: () => deviceService.update(editDevice!.id, {
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
      setMessage("Dispositivo atualizado com sucesso.");
      setEditDevice(null);
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (error) => setFormError(apiErrorMessage(error, "Não foi possível atualizar o dispositivo."))
  });

  const remove = useMutation({
    mutationFn: () => deviceService.delete(deleteDevice!.id),
    onSuccess: (result) => {
      setMessage(result.message || "Dispositivo removido.");
      setDeleteDevice(null);
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["device-status"] });
    },
    onError: (error) => {
      setMessage(deleteErrorMessage(error));
      setDeleteDevice(null);
    }
  });

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateForm(form);
    if (validation) { setFormError(validation); return; }
    setFormError("");
    create.mutate();
  }

  function submitEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateForm(form);
    if (validation) { setFormError(validation); return; }
    setFormError("");
    update.mutate();
  }

  function openEdit(device: Device) {
    setForm(deviceToForm(device));
    setFormError("");
    setEditDevice(device);
  }

  function closeEdit() {
    setEditDevice(null);
    setForm(initialForm);
    setFormError("");
  }

  function closeCreate() {
    setCreateOpen(false);
    setForm(initialForm);
    setFormError("");
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

  function handlePing(device: Device) {
    setPingStates((s) => ({ ...s, [device.id]: "pending" }));
    deviceService.ping(device.id)
      .then(() => {
        setPingStates((s) => ({ ...s, [device.id]: "success" }));
        queryClient.invalidateQueries({ queryKey: ["devices"] });
        setTimeout(() => setPingStates((s) => { const n = { ...s }; delete n[device.id]; return n; }), 3000);
      })
      .catch(() => {
        setPingStates((s) => ({ ...s, [device.id]: "error" }));
        queryClient.invalidateQueries({ queryKey: ["devices"] });
        setTimeout(() => setPingStates((s) => { const n = { ...s }; delete n[device.id]; return n; }), 3000);
      });
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Infraestrutura"
        title="Dispositivos"
        description="Catracas, controladoras e pontos de acesso vinculados as areas fisicas."
        actions={<Button icon={Plus} onClick={() => setCreateOpen(true)}>Novo dispositivo</Button>}
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
                  <p className="flex items-center gap-2"><KeyRound className="h-4 w-4 text-slate-400" /> Credenciais: {hasIntelbrasCredentials(device) ? "configuradas" : "nao configuradas"}</p>
                  <p className="flex items-center gap-2"><Cpu className="h-4 w-4 text-slate-400" /> Ultimo heartbeat: {formatDate(device.lastHeartbeatAt)}</p>
                  {device.lastSuccessAt ? (
                    <p className="flex items-center gap-2"><CheckCircle2 className="h-4 w-4 text-emerald-400" /> Ultimo sucesso: {formatDate(device.lastSuccessAt)}</p>
                  ) : null}
                  {device.lastFailureAt ? (
                    <p className="flex items-center gap-2"><AlertTriangle className="h-4 w-4 text-amber-400" /> Ultima falha: {formatDate(device.lastFailureAt)}</p>
                  ) : null}
                  {device.lastError ? (
                    <p className="flex items-center gap-2 text-rose-400 text-xs break-all"><AlertTriangle className="h-4 w-4 shrink-0" /> {device.lastError}</p>
                  ) : null}
                  {(device.communicationFailures ?? 0) > 0 ? (
                    <p className="text-xs text-amber-400">Falhas consecutivas: {device.communicationFailures}</p>
                  ) : null}
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <Button
                    variant="secondary"
                    icon={Zap}
                    className="h-8 px-3 text-xs"
                    loading={pingStates[device.id] === "pending"}
                    onClick={() => handlePing(device)}
                  >
                    {pingStates[device.id] === "success" ? "Online" : pingStates[device.id] === "error" ? "Falhou" : "Testar"}
                  </Button>
                  <Button variant="secondary" icon={Pencil} className="h-8 px-3 text-xs" onClick={() => openEdit(device)}>
                    Editar
                  </Button>
                  {canRemoveDevices ? (
                    <Button
                      variant="secondary"
                      icon={Trash2}
                      className="h-8 px-3 text-xs text-rose-400 hover:text-rose-300"
                      onClick={() => setDeleteDevice(device)}
                    >
                      Remover
                    </Button>
                  ) : null}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      ) : null}

      {/* Modal: novo dispositivo */}
      <Modal title="Novo dispositivo" description="Cadastre controladoras reais com rede, operacao e credenciais Intelbras." open={createOpen} onClose={closeCreate}>
        <DeviceForm
          form={form}
          formError={formError}
          areas={areas.data ?? []}
          isPending={create.isPending}
          onSubmit={submitCreate}
          onCancel={closeCreate}
          updateForm={updateForm}
          applyIntelbrasPreset={applyIntelbrasPreset}
          submitLabel="Cadastrar"
          passwordPlaceholder="Senha Intelbras"
        />
      </Modal>

      {/* Modal: editar dispositivo */}
      <Modal title="Editar dispositivo" description="Altere os dados da controladora. Deixe a senha em branco para manter a atual." open={editDevice !== null} onClose={closeEdit}>
        <DeviceForm
          form={form}
          formError={formError}
          areas={areas.data ?? []}
          isPending={update.isPending}
          onSubmit={submitEdit}
          onCancel={closeEdit}
          updateForm={updateForm}
          applyIntelbrasPreset={applyIntelbrasPreset}
          submitLabel="Salvar"
          passwordPlaceholder="Nova senha (deixe em branco para manter)"
        />
      </Modal>

      {/* Modal: confirmar exclusão */}
      <Modal title="Remover dispositivo" description={`Tem certeza que deseja remover "${deleteDevice?.name}"? O histórico de eventos será preservado.`} open={deleteDevice !== null} onClose={() => setDeleteDevice(null)}>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setDeleteDevice(null)}>Cancelar</Button>
          <Button loading={remove.isPending} onClick={() => remove.mutate()} className="bg-rose-600 hover:bg-rose-500">Remover</Button>
        </div>
      </Modal>
    </AdminShell>
  );
}

type DeviceFormProps = {
  form: DeviceForm;
  formError: string;
  areas: { id: string; name: string }[];
  isPending: boolean;
  onSubmit: (e: FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
  updateForm: <K extends keyof DeviceForm>(key: K, value: DeviceForm[K]) => void;
  applyIntelbrasPreset: () => void;
  submitLabel: string;
  passwordPlaceholder: string;
};

function DeviceForm({ form, formError, areas, isPending, onSubmit, onCancel, updateForm, applyIntelbrasPreset, submitLabel, passwordPlaceholder }: DeviceFormProps) {
  return (
    <form onSubmit={onSubmit} className="grid gap-5">
      <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-sm font-semibold text-slate-100">Dados básicos</p>
            <p className="mt-1 text-xs text-slate-500">Serial recomendado para auditoria e suporte técnico.</p>
          </div>
          <Button type="button" variant="secondary" icon={Sparkles} className="h-auto min-h-10 whitespace-normal py-2 text-left" onClick={applyIntelbrasPreset}>Preencher modelo Intelbras SS 5531</Button>
        </div>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Input label="Nome" value={form.name} onChange={(e) => updateForm("name", e.target.value)} required />
          <Input label="Modelo" value={form.model} onChange={(e) => updateForm("model", e.target.value)} required />
          <Input label="Número de série" value={form.serialNumber} onChange={(e) => updateForm("serialNumber", e.target.value)} placeholder="DRWL3903457HU" />
          <Select label="Área" value={form.areaId} onChange={(e) => updateForm("areaId", e.target.value)} required>
            <option value="">Selecione</option>
            {areas.map((area) => <option key={area.id} value={area.id}>{area.name}</option>)}
          </Select>
        </div>
      </div>

      <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
        <p className="text-sm font-semibold text-slate-100">Rede</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-[1fr_160px]">
          <Input label="IP" value={form.ipAddress} onChange={(e) => updateForm("ipAddress", e.target.value)} placeholder="192.168.15.5" required />
          <Input label="Porta HTTP" type="number" min={1} max={65535} value={form.httpPort} onChange={(e) => updateForm("httpPort", e.target.value)} required />
        </div>
        <div className="mt-4">
          <Input label="Localização" value={form.location} onChange={(e) => updateForm("location", e.target.value)} placeholder="Portaria principal" />
        </div>
      </div>

      <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
        <p className="text-sm font-semibold text-slate-100">Credenciais Intelbras</p>
        <p className="mt-1 text-xs text-slate-500">Usadas somente pelo backend para comunicação com a controladora.</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Input label="Usuário Intelbras" value={form.intelbrasUsername} onChange={(e) => updateForm("intelbrasUsername", e.target.value)} autoComplete="username" />
          <Input label="Senha Intelbras" type="password" value={form.intelbrasPassword} onChange={(e) => updateForm("intelbrasPassword", e.target.value)} placeholder={passwordPlaceholder} autoComplete="new-password" />
        </div>
      </div>

      <div className="rounded-2xl border border-white/10 bg-white/[0.04] p-4">
        <p className="text-sm font-semibold text-slate-100">Operação</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Select label="Tipo de operação" value={form.operationType} onChange={(e) => updateForm("operationType", e.target.value as OperationType)} required>
            <option value="ENTRY">Entrada</option>
            <option value="EXIT">Saída</option>
            <option value="ENTRY_EXIT">Entrada/Saída</option>
          </Select>
          <Select label="Status" value={form.status} onChange={(e) => updateForm("status", e.target.value as DeviceStatus)} required>
            <option value="ONLINE">Online</option>
            <option value="OFFLINE">Offline</option>
            <option value="MAINTENANCE">Manutenção</option>
            <option value="UNKNOWN">Desconhecido</option>
          </Select>
        </div>
      </div>

      {formError ? <ErrorState label={formError} /> : null}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" type="button" onClick={onCancel}>Cancelar</Button>
        <Button type="submit" loading={isPending}>{submitLabel}</Button>
      </div>
    </form>
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
  if (!form.areaId) return "Selecione a área do dispositivo.";
  if (!Number.isInteger(port) || port < 1 || port > 65535) return "Informe uma porta HTTP entre 1 e 65535.";
  if (intelbrasModel && !form.intelbrasUsername.trim()) return "Informe o usuário Intelbras para este modelo.";
  return "";
}

function formatAddress(ipAddress: string, httpPort?: number) {
  return httpPort && httpPort !== 80 ? `${ipAddress}:${httpPort}` : ipAddress;
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString("pt-BR") : "nao informado";
}

function hasIntelbrasCredentials(device: { intelbrasUsername?: string; intelbrasPasswordConfigured?: boolean }) {
  return Boolean(device.intelbrasUsername || device.intelbrasPasswordConfigured);
}

function deleteErrorMessage(error: unknown) {
  const message = apiErrorMessage(error, "Não foi possível remover o dispositivo.");
  return message === "Forbidden" ? "Apenas ADMIN pode remover dispositivo" : message;
}
