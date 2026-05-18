"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { apiErrorMessage } from "@/lib/errors";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { employeeService } from "@/services/employeeService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, Pencil, Plus, Search, UserX } from "lucide-react";
import { FormEvent, useState } from "react";

export default function EmployeesPage() {
  const queryClient = useQueryClient();
  const employees = useQuery({ queryKey: ["employees"], queryFn: employeeService.list });
  const [fullName, setFullName] = useState("");
  const [cpf, setCpf] = useState("");
  const [email, setEmail] = useState("");
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState("");
  const [open, setOpen] = useState(false);
  const create = useMutation({
    mutationFn: () => employeeService.create({ fullName, cpf, email: email || undefined, status: "ACTIVE" }),
    onSuccess: () => {
      setFullName("");
      setCpf("");
      setEmail("");
      setMessage("Colaborador criado com sucesso.");
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["employees"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o colaborador."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  const filteredEmployees = (employees.data ?? []).filter((employee) => {
    const term = search.trim().toLowerCase();
    if (!term) return true;
    return [employee.fullName, employee.cpf, employee.email].some((value) => value?.toLowerCase().includes(term));
  });

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Pessoas"
        title="Colaboradores"
        description="Cadastro, consulta e acompanhamento da situacao operacional de pessoas internas."
        actions={<Button icon={Plus} onClick={() => setOpen(true)}>Novo colaborador</Button>}
      />
      <Card className="mb-5">
        <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full sm:max-w-md">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Buscar por nome, CPF ou email"
              className="h-10 w-full rounded-lg border border-slate-300 bg-white pl-10 pr-3 text-sm outline-none focus:border-sport-red"
            />
          </div>
          {message ? <p className="text-sm font-medium text-slate-700">{message}</p> : null}
        </CardContent>
      </Card>
      {employees.isLoading ? <LoadingState label="Carregando colaboradores..." /> : null}
      {employees.isError ? <ErrorState label="Não foi possível carregar os colaboradores." /> : null}
      {!employees.isLoading && !employees.isError && employees.data?.length === 0 ? (
        <EmptyState label="Nenhum colaborador cadastrado ainda." description="Use o botao Novo colaborador para iniciar o cadastro." />
      ) : null}
      {!employees.isLoading && !employees.isError && employees.data && employees.data.length > 0 && filteredEmployees.length === 0 ? (
        <EmptyState label="Nenhum colaborador encontrado." description="Ajuste a busca para visualizar outros registros." />
      ) : null}
      {!employees.isLoading && !employees.isError && filteredEmployees.length > 0 ? (
        <DataTable
          data={filteredEmployees}
          getRowKey={(employee) => employee.id}
          columns={[
            { key: "name", header: "Nome", className: "px-4 py-3 font-semibold text-slate-950", render: (employee) => employee.fullName },
            { key: "cpf", header: "CPF", render: (employee) => employee.cpf },
            { key: "email", header: "Email", render: (employee) => employee.email ?? "Nao informado" },
            { key: "status", header: "Status", render: (employee) => <StatusBadge value={employee.status} /> },
            {
              key: "actions",
              header: "Acoes",
              render: () => (
                <div className="flex items-center gap-1">
                  <Button aria-label="Visualizar" variant="ghost" icon={Eye} className="h-9 w-9 px-0" />
                  <Button aria-label="Editar" variant="ghost" icon={Pencil} className="h-9 w-9 px-0" disabled />
                  <Button aria-label="Desativar" variant="ghost" icon={UserX} className="h-9 w-9 px-0" disabled />
                </div>
              )
            }
          ]}
        />
      ) : null}
      <Modal title="Novo colaborador" description="Cadastro basico usando o endpoint atual de colaboradores." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-4">
          <Input label="Nome completo" value={fullName} onChange={(event) => setFullName(event.target.value)} required />
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="CPF" value={cpf} onChange={(event) => setCpf(event.target.value)} required />
            <Input label="Email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
          </div>
          {create.isError ? <ErrorState label={message || "Não foi possível criar o colaborador."} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" loading={create.isPending}>Cadastrar</Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}
