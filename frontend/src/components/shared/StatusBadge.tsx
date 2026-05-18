import { Badge } from "@/src/components/ui/Badge";

const labels: Record<string, string> = {
  ACTIVE: "Ativo",
  INACTIVE: "Inativo",
  BLOCKED: "Bloqueado",
  ONLINE: "Online",
  OFFLINE: "Offline",
  UNKNOWN: "Desconhecido",
  ALLOWED: "Permitido",
  DENIED: "Negado",
  ERROR: "Erro",
  ENTRY: "Entrada",
  EXIT: "Saída",
  ACCESS_DENIED: "Acesso negado",
  COMMUNICATION_FAILURE: "Falha comunicacao",
  INFO: "Info",
  WARNING: "Atencao",
  CRITICAL: "Critico",
  INVITED: "Convidado",
  PENDING_REGISTRATION: "Cadastro pendente",
  COMPLETED: "Completo",
  EXPIRED: "Expirado",
  CANCELLED: "Cancelado",
  SENT: "Enviado",
  SKIPPED: "Pulado",
  FAILED: "Falhou",
  ENTRY_EXIT: "Entrada/Saída",
  SIMULATED: "Simulado",
  DEVICE: "Dispositivo"
};

export function humanizeStatus(value?: string | boolean | null) {
  if (typeof value === "boolean") return value ? "Ativo" : "Inativo";
  if (!value) return "Nao informado";
  return labels[value] ?? value.replace(/_/g, " ").toLowerCase().replace(/^\w/, (letter) => letter.toUpperCase());
}

export function StatusBadge({ value }: { value?: string | boolean | null }) {
  const normalized = typeof value === "boolean" ? (value ? "ACTIVE" : "INACTIVE") : value;
  const tone =
    normalized === "ACTIVE" || normalized === "ONLINE" || normalized === "ALLOWED" || normalized === "SENT"
      ? "green"
      : normalized === "DENIED" || normalized === "BLOCKED" || normalized === "OFFLINE" || normalized === "ERROR" || normalized === "FAILED"
        ? "red"
        : normalized === "UNKNOWN"
          ? "amber"
          : normalized === "ENTRY" || normalized === "EXIT" || normalized === "ENTRY_EXIT"
            ? "blue"
            : "slate";

  return <Badge tone={tone}>{humanizeStatus(value)}</Badge>;
}
