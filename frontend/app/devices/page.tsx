"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { areaService } from "@/services/areaService";
import { deviceService } from "@/services/deviceService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";

export default function DevicesPage() {
  const queryClient = useQueryClient();
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [name, setName] = useState("");
  const [ipAddress, setIpAddress] = useState("");
  const [areaId, setAreaId] = useState("");
  const [message, setMessage] = useState("");
  const create = useMutation({
    mutationFn: () => deviceService.create({ name, ipAddress, areaId, operationType: "ENTRY_EXIT", status: "UNKNOWN" }),
    onSuccess: () => {
      setName("");
      setIpAddress("");
      setMessage("Dispositivo criado com sucesso.");
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
      <PageHeader title="Dispositivos" description="Catracas e controladoras cadastradas." />
      <form onSubmit={submit} className="mb-6 grid gap-3 rounded-md border border-slate-200 bg-white p-4 md:grid-cols-[1fr_160px_1fr_auto]">
        <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Nome" className="h-10 rounded-md border border-slate-300 px-3 text-sm" required />
        <input value={ipAddress} onChange={(event) => setIpAddress(event.target.value)} placeholder="IP" className="h-10 rounded-md border border-slate-300 px-3 text-sm" required />
        <select value={areaId} onChange={(event) => setAreaId(event.target.value)} className="h-10 rounded-md border border-slate-300 px-3 text-sm" required>
          <option value="">Área</option>
          {areas.data?.map((area) => <option key={area.id} value={area.id}>{area.name}</option>)}
        </select>
        <button disabled={create.isPending} className="h-10 rounded-md bg-sport-red px-4 text-sm font-semibold text-white disabled:opacity-60">Cadastrar</button>
      </form>
      {message ? <p className="mb-4 text-sm text-slate-700">{message}</p> : null}
      {devices.isLoading ? <LoadingState label="Carregando dispositivos..." /> : null}
      {devices.isError ? <ErrorState label="Não foi possível carregar os dispositivos." /> : null}
      {!devices.isLoading && !devices.isError && devices.data?.length === 0 ? (
        <EmptyState label="Nenhum dispositivo cadastrado ainda." />
      ) : null}
      {!devices.isLoading && !devices.isError && devices.data && devices.data.length > 0 ? (
      <div className="grid gap-3">
        {devices.data?.map((device) => (
          <div key={device.id} className="rounded-md border border-slate-200 bg-white p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="font-semibold text-slate-950">{device.name}</p>
                <p className="text-sm text-slate-500">{device.model ?? "Modelo não informado"} · {device.ipAddress}</p>
              </div>
              <span className="rounded-md bg-slate-100 px-2 py-1 text-xs font-medium text-slate-700">{device.status}</span>
            </div>
            <p className="mt-3 text-sm text-slate-600">{device.areaName} · {device.location ?? "Sem local"}</p>
          </div>
        ))}
      </div>
      ) : null}
    </AdminShell>
  );
}
