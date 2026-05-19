"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input, Textarea } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { areaService } from "@/services/areaService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPinned, Plus } from "lucide-react";
import { FormEvent, useState } from "react";

export default function AreasPage() {
  const queryClient = useQueryClient();
  const areas = useQuery({ queryKey: ["areas"], queryFn: areaService.list });
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [message, setMessage] = useState("");
  const [open, setOpen] = useState(false);
  const create = useMutation({
    mutationFn: () => areaService.create({ name, description }),
    onSuccess: () => {
      setName("");
      setDescription("");
      setMessage("Área criada com sucesso.");
      setOpen(false);
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
      <PageHeader
        eyebrow="Mapa operacional"
        title="Áreas"
        description="Zonas fisicas usadas nas regras de permissao e na leitura operacional dos eventos."
        actions={<Button icon={Plus} onClick={() => setOpen(true)}>Nova area</Button>}
      />
      {message ? <div className="mb-4 rounded-xl border border-white/10 bg-white/[0.055] px-4 py-3 text-sm font-medium text-slate-300 shadow-sm">{message}</div> : null}
      {areas.isLoading ? <LoadingState label="Carregando áreas..." /> : null}
      {areas.isError ? <ErrorState label="Não foi possível carregar as áreas." /> : null}
      {!areas.isLoading && !areas.isError && areas.data?.length === 0 ? (
        <EmptyState label="Nenhuma área cadastrada ainda." description="Cadastre areas para organizar os dispositivos e eventos." />
      ) : null}
      {!areas.isLoading && !areas.isError && areas.data && areas.data.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {areas.data?.map((area) => (
            <Card key={area.id}>
              <CardContent>
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-start gap-3">
                    <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-red-300/20 bg-red-500/12 text-red-200">
                      <MapPinned className="h-5 w-5" />
                    </div>
                    <div>
                      <p className="font-semibold text-slate-50">{area.name}</p>
                      <p className="mt-2 text-sm leading-6 text-slate-400">{area.description ?? "Sem descricao"}</p>
                    </div>
                  </div>
                  <StatusBadge value={area.active} />
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      ) : null}
      <Modal title="Nova area" description="Crie uma zona fisica para associar dispositivos e regras." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-4">
          <Input label="Nome da area" value={name} onChange={(event) => setName(event.target.value)} required />
          <Textarea label="Descricao" value={description} onChange={(event) => setDescription(event.target.value)} />
          {create.isError ? <ErrorState label={message || "Não foi possível criar a área."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Cadastrar</Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}
