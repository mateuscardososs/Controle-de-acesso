"use client";

import { AdminShell } from "@/components/AdminShell";
import { EmptyState, ErrorState, LoadingState } from "@/components/AsyncState";
import { PageHeader } from "@/components/PageHeader";
import { formatCpfDisplay, formatCpfInput } from "@/lib/cpf";
import { apiErrorMessage } from "@/lib/errors";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input, Select } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { DataTable } from "@/src/components/shared/DataTable";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { CameraCapture } from "@/src/components/shared/CameraCapture";
import { employeeService, Employee } from "@/services/employeeService";
import { adminCleanupService } from "@/services/adminCleanupService";
import { authService } from "@/services/authService";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { FormEvent, useState } from "react";

export default function EmployeesPage() {
  const queryClient = useQueryClient();
  const employees = useQuery({ queryKey: ["employees"], queryFn: employeeService.list });
  const user = useQuery({ queryKey: ["me"], queryFn: authService.me, retry: false });
  const canManage = user.data?.role === "ADMIN" || user.data?.role === "HR";
  const canCleanup = user.data?.role === "ADMIN";

  const [fullName, setFullName] = useState("");
  const [cpf, setCpf] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [cardNo, setCardNo] = useState("");
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [role, setRole] = useState<"ADMIN" | "HR" | "SECURITY_VIEWER">("SECURITY_VIEWER");
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState("");
  const [open, setOpen] = useState(false);
  const [syncingId, setSyncingId] = useState<string | null>(null);

  const [confirmDelete, setConfirmDelete] = useState<Employee | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [cleanupOpen, setCleanupOpen] = useState(false);
  const [cleanupConfirmation, setCleanupConfirmation] = useState("");

  const create = useMutation({
    mutationFn: () => employeeService.create({ fullName, cpf, email, password, role, cardNo, facePhoto, status: "ACTIVE" }),
    onSuccess: () => {
      setFullName("");
      setCpf("");
      setEmail("");
      setPassword("");
      setCardNo("");
      setFacePhoto(null);
      setRole("SECURITY_VIEWER");
      setMessage("Colaborador criado. Sincronize com a controladora quando estiver pronto.");
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["employees"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível criar o colaborador."))
  });

  const sync = useMutation({
    mutationFn: (id: string) => employeeService.sync(id),
    onMutate: (id) => {
      setSyncingId(id);
      setMessage("Sincronização de colaborador enfileirada.");
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["employees"] }),
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível sincronizar o colaborador.")),
    onSettled: () => setSyncingId(null)
  });

  const deactivate = useMutation({
    mutationFn: (id: string) => employeeService.deactivate(id),
    onSuccess: () => {
      setConfirmDelete(null);
      setDeleteError("");
      queryClient.invalidateQueries({ queryKey: ["employees"] });
    },
    onError: (error) => setDeleteError(apiErrorMessage(error, "Não foi possível desativar o colaborador."))
  });

  const cleanupEmployees = useMutation({
    mutationFn: () => adminCleanupService.employees(cleanupConfirmation),
    onSuccess: (response) => {
      setCleanupOpen(false);
      setCleanupConfirmation("");
      setMessage(response.message);
      queryClient.invalidateQueries({ queryKey: ["employees"] });
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível limpar colaboradores."))
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
        description="Cadastro, perfil de acesso ao admin e situação operacional de pessoas internas."
        actions={
          <div className="flex flex-wrap gap-2">
            {canCleanup ? (
              <Button variant="danger" icon={Trash2} className="h-12 px-5 text-base" onClick={() => { setCleanupOpen(true); setMessage(""); }}>
                Limpar lista
              </Button>
            ) : null}
            <Button icon={Plus} className="h-12 px-5 text-base" onClick={() => setOpen(true)}>Novo colaborador</Button>
          </div>
        }
      />
      <Card className="mb-5">
        <CardContent className="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full sm:max-w-md">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Buscar por nome, CPF ou email"
              className="h-10 w-full rounded-xl border border-white/10 bg-white/[0.055] pl-10 pr-3 text-sm text-slate-100 outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]"
            />
          </div>
          {message ? <p className="text-sm font-medium text-slate-300">{message}</p> : null}
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
            { key: "name", header: "Nome", className: "font-semibold text-slate-100", render: (employee) => employee.fullName },
            { key: "cpf", header: "CPF", className: "whitespace-nowrap font-mono text-xs", render: (employee) => formatCpfDisplay(employee.cpf) },
            { key: "email", header: "Email", render: (employee) => employee.email ?? "Nao informado" },
            { key: "cardNo", header: "Tag/cartão", render: (employee) => employee.cardNo ?? "Nao informado" },
            { key: "role", header: "Perfil", render: (employee) => employee.role ? <StatusBadge value={employee.role} /> : "Sem acesso" },
            { key: "status", header: "Status", render: (employee) => <StatusBadge value={employee.status} /> },
            { key: "sync", header: "Integração", render: (employee) => <StatusBadge value={employee.syncStatus ?? "PENDING_SYNC"} /> },
            {
              key: "actions",
              header: "Ações",
              className: "min-w-[280px]",
              render: (employee) => {
                if (!canManage) return <span className="text-xs text-slate-500">—</span>;
                const isSyncing = sync.isPending && syncingId === employee.id;
                return (
                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      aria-label={`Sincronizar ${employee.fullName}`}
                      variant="secondary"
                      icon={isSyncing ? Loader2 : RefreshCw}
                      className="h-9 px-4 text-sm"
                      disabled={isSyncing || employee.status !== "ACTIVE" || (!employee.facePhotoUrl && !employee.cardNo)}
                      onClick={() => sync.mutate(employee.id)}
                    >
                      Sincronizar
                    </Button>
                    <Button
                      aria-label="Editar"
                      variant="ghost"
                      icon={Pencil}
                      className="h-9 px-4 text-sm"
                      disabled
                    >
                      Editar
                    </Button>
                    <Button
                      aria-label={`Desativar ${employee.fullName}`}
                      variant="ghost"
                      icon={Trash2}
                      className="h-9 px-4 text-sm text-rose-400 hover:text-rose-300"
                      disabled={employee.status === "INACTIVE"}
                      onClick={() => { setConfirmDelete(employee); setDeleteError(""); }}
                    >
                      Desativar
                    </Button>
                  </div>
                );
              }
            }
          ]}
        />
      ) : null}

      <Modal title="Novo colaborador" description="Cadastro com acesso ao painel administrativo." open={open} onClose={() => setOpen(false)}>
        <form onSubmit={submit} className="grid gap-5">
          <Input label="Nome completo" value={fullName} onChange={(event) => setFullName(event.target.value)} required />
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="CPF" value={cpf} onChange={(event) => setCpf(formatCpfInput(event.target.value))} required inputMode="numeric" placeholder="000.000.000-00" />
            <Input label="Email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input label="Senha" type="password" minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} required />
            <Select label="Perfil" value={role} onChange={(event) => setRole(event.target.value as typeof role)} required>
              <option value="ADMIN">ADMIN</option>
              <option value="HR">HR</option>
              <option value="SECURITY_VIEWER">SECURITY_VIEWER</option>
            </Select>
          </div>
          <Input label="Tag/cartão" value={cardNo} onChange={(event) => setCardNo(event.target.value)} />
          <div className="rounded-xl border border-white/10 bg-white/[0.045] p-3">
            <p className="mb-3 text-sm font-semibold text-slate-200">Foto facial</p>
            <CameraCapture value={facePhoto} onChange={setFacePhoto} disabled={create.isPending} />
          </div>
          <div className="rounded-xl border border-white/10 bg-white/[0.045] px-4 py-3 text-sm text-slate-300">
            A validade física é calculada automaticamente para o mês atual.
          </div>
          {create.isError ? <ErrorState label={message || "Não foi possível criar o colaborador."} /> : null}
          <div className="flex flex-wrap justify-end gap-3 pt-1">
            <Button variant="secondary" className="h-11 px-5" onClick={() => setOpen(false)}>Cancelar</Button>
            <Button type="submit" className="h-11 px-5" loading={create.isPending}>Cadastrar</Button>
          </div>
        </form>
      </Modal>

      <Modal
        title="Desativar colaborador"
        description="Esta ação desativa o acesso do colaborador ao sistema."
        open={!!confirmDelete}
        onClose={() => { if (!deactivate.isPending) { setConfirmDelete(null); setDeleteError(""); } }}
      >
        <div className="space-y-4">
          <p className="text-sm text-slate-300">
            Tem certeza que deseja desativar <span className="font-semibold text-slate-100">{confirmDelete?.fullName}</span>?
            Esta ação revoga o acesso e não pode ser desfeita sem reativação manual.
          </p>
          {deleteError ? <ErrorState label={deleteError} /> : null}
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" disabled={deactivate.isPending} onClick={() => { setConfirmDelete(null); setDeleteError(""); }}>
              Cancelar
            </Button>
            <Button
              icon={Trash2}
              className="bg-rose-700 hover:bg-rose-600 text-white"
              loading={deactivate.isPending}
              onClick={() => confirmDelete && deactivate.mutate(confirmDelete.id)}
            >
              Confirmar desativação
            </Button>
          </div>
        </div>
      </Modal>

      <Modal
        title="Limpar lista de colaboradores"
        description="Remove colaboradores não administrativos e usuários vinculados que não sejam ADMIN. O admin logado e usuários ADMIN essenciais são preservados."
        open={cleanupOpen}
        onClose={() => {
          if (!cleanupEmployees.isPending) {
            setCleanupOpen(false);
            setCleanupConfirmation("");
          }
        }}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            cleanupEmployees.mutate();
          }}
          className="space-y-4"
        >
          <div className="rounded-xl border border-amber-300/20 bg-amber-400/12 p-3 text-sm text-amber-100">
            Digite <span className="font-semibold">LIMPAR_COLABORADORES</span> para confirmar. Visitantes, dispositivos e áreas não serão apagados.
          </div>
          <Input label="Confirmação" value={cleanupConfirmation} onChange={(event) => setCleanupConfirmation(event.target.value)} />
          {cleanupEmployees.isError ? <ErrorState label={message || "Não foi possível limpar colaboradores."} /> : null}
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" disabled={cleanupEmployees.isPending} onClick={() => setCleanupOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" variant="danger" icon={Trash2} loading={cleanupEmployees.isPending} disabled={cleanupConfirmation !== "LIMPAR_COLABORADORES"}>
              Limpar colaboradores
            </Button>
          </div>
        </form>
      </Modal>
    </AdminShell>
  );
}
