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
import { Cpu, MapPin, Plus, Router, Wifi } from "lucide-react";
import { FormEvent, useState } from "react";

export default function DevicesPage() {
  const queryClient = useQueryClient();
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [name, setName] = useState("");
  const [model, setModel] = useState("");
  const [ipAddress, setIpAddress] = useState("");
  const [areaId, setAreaId] = useState("");
  const [message, setMessage] = useState("");
  const [open, setOpen] = useState(false);
  const create = useMutation({
    mutationFn: () => deviceService.create({ name, model: model || undefined, ipAddress, areaId, operationType: "ENTRY_EXIT", status: "UNKNOWN" }),
    onSuccess: () => {
      setName("");
      setModel("");
      setIpAddress("");
      setMessage("Dispositivo criado com sucesso.");
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o dispositivo."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Infraestrutura"
        title="Dispositivos"
        description="Catracas, controladoras e pontos de acesso vinculados as areas fisicas."
        actions={<Button icon={Plus} onClick={() => setOpen(true)}>Novo dispositivo</Button>}
      />
      {message ? <div className="mb-4 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm font-medium text-slate-700 shadow-sm">{message}</div> : null}
      {devices.isLoading ? <LoadingState label="Carregando dispositivos..." /> : null}
      {devices.isError ? <ErrorState label="Não foi possível carregar os dispositivos." /> : null}
      {!devices.isLoading && !devices.isError && devices.data?.length === 0 ? (
        <EmptyState label="Nenhum dispositivo cadastrado ainda." description="Cadastre um dispositivo para acompanhar sua situacao operacional." />
      ) : null}
      {!devices.isLoading && !devices.isError && devices.data && devices.data.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {devices.data?.map((device) => (
            <Card key={device.id} className={device.status === "OFFLINE" || device.status === "UNKNOWN" ? "border-amber-200" : undefined}>
              <CardContent>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-slate-100 text-slate-700">
                      <Router className="h-5 w-5" />
                    </div>
                    <div>
                      <p className="font-semibold text-slate-950">{device.name}</p>
                      <p className="mt-1 text-sm text-slate-500">{device.model ?? "Modelo nao informado"}</p>
                    </div>
                  </div>
                  <StatusBadge value={device.status} />
                </div>
                <div className="mt-5 grid gap-3 text-sm text-slate-600">
                  <p className="flex items-center gap-2"><Wifi className="h-4 w-4 text-slate-400" /> {device.ipAddress}</p>
                  <p className="flex items-center gap-2"><MapPin className="h-4 w-4 text-slate-400" /> {device.areaName} · {device.location ?? "Sem local"}</p>
                  <p className="flex items-center gap-2"><Cpu className="h-4 w-4 text-slate-400" /> Ultima comunicacao: nao informada</p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      ) : null}
      <Modal title="Novo dispositivo" description="Cadastro usando o contrato atual de dispositivos." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Nome" value={name} onChange={(event) => setName(event.target.value)} required />
            <Input label="Modelo" value={model} onChange={(event) => setModel(event.target.value)} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="IP" value={ipAddress} onChange={(event) => setIpAddress(event.target.value)} required />
            <Select label="Area" value={areaId} onChange={(event) => setAreaId(event.target.value)} required>
              <option value="">Selecione</option>
              {areas.data?.map((area) => <option key={area.id} value={area.id}>{area.name}</option>)}
            </Select>
          </div>
          {create.isError ? <ErrorState label={message || "Não foi possível criar o dispositivo."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Cadastrar</Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}
