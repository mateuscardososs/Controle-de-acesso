"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { employeeService } from "@/services/employeeService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";

export default function EmployeesPage() {
  const queryClient = useQueryClient();
  const employees = useQuery({ queryKey: ["employees"], queryFn: employeeService.list });
  const [fullName, setFullName] = useState("");
  const [cpf, setCpf] = useState("");
  const [message, setMessage] = useState("");
  const create = useMutation({
    mutationFn: () => employeeService.create({ fullName, cpf, status: "ACTIVE" }),
    onSuccess: () => {
      setFullName("");
      setCpf("");
      setMessage("Colaborador criado com sucesso.");
      queryClient.invalidateQueries({ queryKey: ["employees"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o colaborador."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AdminShell>
      <PageHeader title="Colaboradores" description="Cadastro e consulta de pessoas internas." />
      <form onSubmit={submit} className="mb-6 grid gap-3 rounded-md border border-slate-200 bg-white p-4 md:grid-cols-[1fr_180px_auto]">
        <input value={fullName} onChange={(event) => setFullName(event.target.value)} placeholder="Nome completo" className="h-10 rounded-md border border-slate-300 px-3 text-sm" required />
        <input value={cpf} onChange={(event) => setCpf(event.target.value)} placeholder="CPF" className="h-10 rounded-md border border-slate-300 px-3 text-sm" required />
        <button disabled={create.isPending} className="h-10 rounded-md bg-sport-red px-4 text-sm font-semibold text-white disabled:opacity-60">Cadastrar</button>
      </form>
      {message ? <p className="mb-4 text-sm text-slate-700">{message}</p> : null}
      {employees.isLoading ? <LoadingState label="Carregando colaboradores..." /> : null}
      {employees.isError ? <ErrorState label="Não foi possível carregar os colaboradores." /> : null}
      {!employees.isLoading && !employees.isError && employees.data?.length === 0 ? (
        <EmptyState label="Nenhum colaborador cadastrado ainda." />
      ) : null}
      {!employees.isLoading && !employees.isError && employees.data && employees.data.length > 0 ? (
      <div className="overflow-hidden rounded-md border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr><th className="p-3">Nome</th><th className="p-3">CPF</th><th className="p-3">Status</th></tr>
          </thead>
          <tbody>
            {employees.data?.map((employee) => (
              <tr key={employee.id} className="border-t border-slate-200">
                <td className="p-3 font-medium text-slate-900">{employee.fullName}</td>
                <td className="p-3 text-slate-600">{employee.cpf}</td>
                <td className="p-3 text-slate-600">{employee.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      ) : null}
    </AdminShell>
  );
}
