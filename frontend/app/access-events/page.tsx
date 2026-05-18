"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { accessEventService } from "@/services/accessEventService";
import { useQuery } from "@tanstack/react-query";

export default function AccessEventsPage() {
  const events = useQuery({ queryKey: ["access-events"], queryFn: accessEventService.list });

  return (
    <AdminShell>
      <PageHeader title="Eventos" description="Histórico de acessos recebidos e simulados." />
      {events.isLoading ? <LoadingState label="Carregando eventos..." /> : null}
      {events.isError ? <ErrorState label="Não foi possível carregar os eventos." /> : null}
      {!events.isLoading && !events.isError && events.data?.length === 0 ? (
        <EmptyState label="Nenhum evento registrado ainda." />
      ) : null}
      {!events.isLoading && !events.isError && events.data && events.data.length > 0 ? (
      <div className="overflow-hidden rounded-md border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr><th className="p-3">Horário</th><th className="p-3">Tipo</th><th className="p-3">Resultado</th><th className="p-3">Origem</th></tr>
          </thead>
          <tbody>
            {events.data?.map((event) => (
              <tr key={event.id} className="border-t border-slate-200">
                <td className="p-3 text-slate-600">{new Date(event.eventTime).toLocaleString("pt-BR")}</td>
                <td className="p-3 text-slate-600">{event.eventType}</td>
                <td className="p-3 font-medium text-slate-950">{event.accessResult}</td>
                <td className="p-3 text-slate-600">{event.origin}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      ) : null}
    </AdminShell>
  );
}
