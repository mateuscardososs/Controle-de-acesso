"use client";

import { Area } from "@/services/areaService";
import { Employee, EmployeeUpdatePayload } from "@/services/employeeService";
import { Button } from "@/src/components/ui/Button";
import { Input } from "@/src/components/ui/Input";
import { Modal } from "@/src/components/ui/Modal";
import { StatusBadge } from "@/src/components/shared/StatusBadge";
import { displayAreaName } from "@/lib/areaLabels";
import { formatCpfDisplay } from "@/lib/cpf";
import { AxiosError } from "axios";
import { Maximize2, X } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";

type FieldErrors = Partial<Record<"fullName" | "email" | "phone" | "jobTitle" | "allowedAreaIds", string>>;
type ApiErrorBody = { error?: string; details?: string[] };
type EmployeeEditValues = EmployeeUpdatePayload & { id: string; areasChanged: boolean };

type Props = {
  employee: Employee | null;
  areas: Area[];
  open: boolean;
  onClose: () => void;
  onSave: (values: EmployeeEditValues) => Promise<void>;
  onServerError: (message: string) => void;
};

/**
 * Modal de edição de colaborador.
 *
 * Todos os hooks são chamados incondicionalmente no topo.
 * O guard "if (!open || !employee) return null" fica DEPOIS dos hooks
 * para não violar as regras do React.
 */
export function EmployeeEditModal({ employee, areas, open, onClose, onSave, onServerError }: Props) {
  // ── estado do formulário ──────────────────────────────────────────────
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [jobTitle, setJobTitle] = useState("");
  const [selectedAreaIds, setSelectedAreaIds] = useState<string[]>([]);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const [imageFailed, setImageFailed] = useState(false);

  // ── lista de áreas ativas (seguro contra name nulo) ───────────────────
  const activeAreas = useMemo(
    () =>
      (areas ?? [])
        .filter((a) => a.active)
        .sort((a, b) => (a.name ?? "").localeCompare(b.name ?? "")),
    [areas]
  );

  // ── sincroniza form quando o colaborador selecionado muda ─────────────
  useEffect(() => {
    if (!open || !employee) return;
    setFullName(employee.fullName ?? "");
    setEmail(employee.email ?? "");
    setPhone(employee.phone ?? "");
    setJobTitle(employee.jobTitle ?? "");
    setSelectedAreaIds(employee.allowedAreaIds ?? []);
    setFieldErrors({});
    setFormError("");
    setImageFailed(false);
    setLightboxOpen(false);
  }, [open, employee?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── guard (APÓS todos os hooks) ───────────────────────────────────────
  if (!open || !employee) return null;

  // TypeScript não rastreia narrowing de 'employee' dentro de closures async;
  // capturamos numa const local para ter Employee (não null) no fechamento.
  const emp: Employee = employee;

  // valores derivados (só chegamos aqui quando open && employee existem)
  const photoUrl = resolvePhotoUrl(emp.facePhotoUrl);
  const hasPhoto = Boolean(photoUrl) && !imageFailed;
  const initials = getInitials(emp.fullName ?? "");

  // ── submit ────────────────────────────────────────────────────────────
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;

    const errors = validateFields(fullName, email, selectedAreaIds);
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setFormError("");
      return;
    }

    setSaving(true);
    setFieldErrors({});
    setFormError("");
    try {
      await onSave({
        id: emp.id,
        fullName: fullName.trim(),
        email: email.trim(),
        phone: phone.trim() || undefined,
        jobTitle: jobTitle.trim() || undefined,
        allowedAreaIds: selectedAreaIds,
        areasChanged: !sameSet(emp.allowedAreaIds ?? [], selectedAreaIds),
      });
      onClose();
    } catch (err) {
      const status = (err as AxiosError<ApiErrorBody>).response?.status;
      if (status === 400 || status === 422) {
        const { fields, formError: fe } = parseApiErrors(err);
        setFieldErrors(fields);
        setFormError(fe);
      } else if (status && status >= 500) {
        onServerError("Erro interno ao salvar colaborador. Tente novamente em instantes.");
      } else {
        setFormError("Não foi possível salvar o colaborador.");
      }
    } finally {
      setSaving(false);
    }
  }

  function toggleArea(id: string) {
    setSelectedAreaIds((prev) =>
      prev.includes(id) ? prev.filter((a) => a !== id) : [...prev, id]
    );
    setFieldErrors((prev) => ({ ...prev, allowedAreaIds: undefined }));
  }

  // ── render ────────────────────────────────────────────────────────────
  return (
    <>
      <Modal
        title="Editar colaborador"
        description="Dados operacionais e áreas de acesso."
        open={true}
        onClose={() => { if (!saving) onClose(); }}
      >
        <form onSubmit={submit} className="grid gap-5">

          {/* Avatar + nome + badges */}
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
            <button
              type="button"
              disabled={!hasPhoto}
              onClick={() => { if (hasPhoto) setLightboxOpen(true); }}
              className="group relative h-20 w-20 shrink-0 overflow-hidden rounded-full border border-white/15 bg-white/[0.055] shadow-sm disabled:cursor-default"
              aria-label={hasPhoto ? "Ampliar foto" : "Sem foto cadastrada"}
            >
              {hasPhoto ? (
                <>
                  <img
                    src={photoUrl}
                    alt={`Foto de ${emp.fullName}`}
                    className="h-full w-full object-cover"
                    onError={() => setImageFailed(true)}
                  />
                  <span className="absolute inset-0 grid place-items-center bg-slate-950/0 opacity-0 transition group-hover:bg-slate-950/45 group-hover:opacity-100">
                    <Maximize2 className="h-5 w-5 text-white" />
                  </span>
                </>
              ) : (
                <span className="grid h-full w-full place-items-center text-lg font-bold text-slate-400 select-none">
                  {initials || "?"}
                </span>
              )}
            </button>

            <div className="flex flex-col gap-1.5">
              <p className="text-base font-semibold text-slate-100">{emp.fullName}</p>
              <div className="flex flex-wrap gap-2">
                <StatusBadge value={emp.status} />
                {emp.role ? <StatusBadge value={emp.role} /> : null}
                <StatusBadge value={emp.syncStatus ?? "PENDING_SYNC"} />
              </div>
            </div>
          </div>

          {/* Painel somente-leitura */}
          <div className="rounded-xl border border-white/10 bg-white/[0.03] p-4">
            <p className="mb-3 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              Informações
            </p>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
              <InfoItem label="CPF" value={formatCpfDisplay(emp.cpf)} mono />
              <InfoItem label="Matrícula" value={emp.registrationNumber} mono />
              <InfoItem label="Cartão" value={emp.cardNo} mono />
              <InfoItem label="Cartão Intelbras" value={emp.intelbrasCardNo} mono />
              <InfoItem label="Válido de" value={fmtDate(emp.accessValidFrom)} />
              <InfoItem label="Válido até" value={fmtDate(emp.accessValidUntil)} />
            </dl>
          </div>

          {/* Campos editáveis */}
          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              label="Nome completo"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={fieldErrors.fullName}
              required
            />
            <Input
              label="Email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={fieldErrors.email}
              required
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              label="Telefone"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              error={fieldErrors.phone}
            />
            <Input
              label="Cargo"
              value={jobTitle}
              onChange={(e) => setJobTitle(e.target.value)}
              error={fieldErrors.jobTitle}
            />
          </div>

          {/* Áreas de acesso */}
          <div>
            <div className="mb-2 flex items-center justify-between gap-3">
              <p className="text-sm font-medium text-slate-300">Áreas de acesso</p>
              <span className="text-xs text-slate-500">{selectedAreaIds.length} selecionada(s)</span>
            </div>
            <div className="grid max-h-52 gap-2 overflow-y-auto rounded-xl border border-white/10 bg-white/[0.045] p-3 sm:grid-cols-2">
              {activeAreas.length === 0 ? (
                <p className="text-sm text-slate-500">Nenhuma área ativa encontrada.</p>
              ) : (
                activeAreas.map((area) => (
                  <label
                    key={area.id}
                    className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-white/10 bg-white/[0.04] px-3 py-2 text-sm font-medium text-slate-200 transition hover:border-white/20 hover:bg-white/[0.075]"
                  >
                    <input
                      type="checkbox"
                      checked={selectedAreaIds.includes(area.id)}
                      onChange={() => toggleArea(area.id)}
                      className="h-4 w-4 accent-brand-wine"
                    />
                    <span>{displayAreaName(area.name)}</span>
                  </label>
                ))
              )}
            </div>
            {fieldErrors.allowedAreaIds ? (
              <p className="mt-1 text-xs font-medium text-red-200">{fieldErrors.allowedAreaIds}</p>
            ) : null}
          </div>

          {formError ? (
            <p className="rounded-xl border border-red-300/20 bg-red-500/10 px-3 py-2 text-sm text-red-100">
              {formError}
            </p>
          ) : null}

          <div className="flex flex-wrap justify-end gap-3 pt-1">
            <Button variant="secondary" className="h-11 px-5" disabled={saving} onClick={onClose}>
              Cancelar
            </Button>
            <Button type="submit" className="h-11 px-5" loading={saving}>
              Salvar
            </Button>
          </div>
        </form>
      </Modal>

      {/* Lightbox da foto */}
      {lightboxOpen && hasPhoto ? (
        <div
          className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-950/90 p-4 backdrop-blur-sm"
          onClick={() => setLightboxOpen(false)}
        >
          <button
            type="button"
            aria-label="Fechar foto"
            className="absolute right-4 top-4 rounded-full bg-white/10 p-2 text-white transition hover:bg-white/20"
            onClick={() => setLightboxOpen(false)}
          >
            <X className="h-5 w-5" />
          </button>
          <img
            src={photoUrl}
            alt={`Foto de ${emp.fullName}`}
            className="max-h-[86vh] max-w-[92vw] rounded-xl object-contain shadow-enterprise"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      ) : null}
    </>
  );
}

// ── componentes auxiliares ─────────────────────────────────────────────────

function InfoItem({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-[10px] font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className={`text-sm text-slate-200 ${mono ? "font-mono" : ""}`}>{value || "—"}</dd>
    </div>
  );
}

// ── funções utilitárias ────────────────────────────────────────────────────

function getInitials(name: string) {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

function fmtDate(value?: string) {
  if (!value) return undefined;
  try {
    return new Date(value).toLocaleDateString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    });
  } catch {
    return value;
  }
}

function resolvePhotoUrl(value?: string) {
  if (!value) return "";
  if (/^https?:\/\//i.test(value)) return value;
  const base = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
  if (value.startsWith("/")) return `${base}${value}`;
  return value;
}

function validateFields(fullName: string, email: string, areas: string[]): FieldErrors {
  const errors: FieldErrors = {};
  if (!fullName.trim()) errors.fullName = "Informe o nome completo.";
  if (!email.trim()) errors.email = "Informe o email.";
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) errors.email = "Informe um email válido.";
  if (areas.length === 0) errors.allowedAreaIds = "Selecione pelo menos uma área ativa.";
  return errors;
}

function parseApiErrors(error: unknown): { fields: FieldErrors; formError: string } {
  const axiosError = error as AxiosError<ApiErrorBody>;
  const body = axiosError.response?.data;
  const fields: FieldErrors = {};
  const messages = body?.details?.length ? body.details : body?.error ? [body.error] : [];

  for (const msg of messages) {
    const fieldMatch = msg.match(/Field '([^']+)' (.+)/);
    if (fieldMatch) {
      const key = normalizeField(fieldMatch[1]);
      fields[key] = humanizeMessage(fieldMatch[2]);
      continue;
    }
    if (/área|area/i.test(msg)) { fields.allowedAreaIds = msg; continue; }
    if (/email/i.test(msg)) fields.email = msg;
  }

  const formError = Object.keys(fields).length === 0
    ? messages.join(" ") || "Revise os campos destacados."
    : "";
  return { fields, formError };
}

function normalizeField(field: string): keyof FieldErrors {
  const map: Record<string, keyof FieldErrors> = {
    allowedAreaIds: "allowedAreaIds",
    jobTitle: "jobTitle",
    phone: "phone",
    email: "email",
    fullName: "fullName",
  };
  return map[field] ?? "fullName";
}

function humanizeMessage(msg: string) {
  if (/must not be blank/i.test(msg)) return "Campo obrigatório.";
  if (/well-formed email/i.test(msg)) return "Informe um email válido.";
  return msg;
}

function sameSet(a: string[], b: string[]) {
  if (a.length !== b.length) return false;
  const setB = new Set(b);
  return a.every((v) => setB.has(v));
}
