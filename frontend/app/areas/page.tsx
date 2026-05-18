"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { areaService } from "@/services/areaService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";

export default function AreasPage() {
  const queryClient = useQueryClient();
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [message, setMessage] = useState("");
  const create = useMutation({
    mutationFn: () => areaService.create({ name, description }),
    onSuccess: () => {
      setName("");
      setDescription("");
      setMessage("Área criada com sucesso.");
      queryClient.invalidateQueries({ queryKey: ["areas"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar a área."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AdminShell>
      <PageHeader title="Áreas" description="Zonas físicas usadas nas regras de permissão." />
      <form onSubmit={submit} className="mb-6 grid gap-3 rounded-md border border-slate-200 bg-white p-4 md:grid-cols-[1fr_1fr_auto]">
        <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Nome da área" className="h-10 rounded-md border border-slate-300 px-3 text-sm" required />
        <input value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Descrição" className="h-10 rounded-md border border-slate-300 px-3 text-sm" />
        <button disabled={create.isPending} className="h-10 rounded-md bg-sport-red px-4 text-sm font-semibold text-white disabled:opacity-60">Cadastrar</button>
      </form>
      {message ? <p className="mb-4 text-sm text-slate-700">{message}</p> : null}
      {areas.isLoading ? <LoadingState label="Carregando áreas..." /> : null}
      {areas.isError ? <ErrorState label="Não foi possível carregar as áreas." /> : null}
      {!areas.isLoading && !areas.isError && areas.data?.length === 0 ? (
        <EmptyState label="Nenhuma área cadastrada ainda." />
      ) : null}
      {!areas.isLoading && !areas.isError && areas.data && areas.data.length > 0 ? (
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {areas.data?.map((area) => (
          <div key={area.id} className="rounded-md border border-slate-200 bg-white p-4">
            <div className="flex items-center justify-between gap-3">
              <p className="font-semibold text-slate-950">{area.name}</p>
              <span className="rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-700">{area.active ? "Ativa" : "Inativa"}</span>
            </div>
            <p className="mt-2 text-sm text-slate-600">{area.description ?? "Sem descrição"}</p>
          </div>
        ))}
      </div>
      ) : null}
    </AdminShell>
  );
}
