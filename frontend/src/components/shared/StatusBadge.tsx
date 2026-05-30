import { Badge } from "@/src/components/ui/Badge";

const labels: Record<string, string> = {
  ACTIVE: "Ativo",
  INACTIVE: "Inativo",
  BLOCKED: "Bloqueado",
  ONLINE: "Online",
  OFFLINE: "Offline",
  MAINTENANCE: "Manutenção",
  UNKNOWN: "Desconhecido",
  ALLOWED: "Liberado",
  DENIED: "Negado",
  ERROR: "Erro",
  ENTRY: "Entrada",
  EXIT: "Saída",
  ACCESS_DENIED: "Acesso negado",
  COMMUNICATION_FAILURE: "Falha de comunicação",
  MANUAL_ADMIN_RELEASE: "Liberação manual",
  RECOGNIZED: "Reconhecido",
  NOT_RECOGNIZED: "Não reconhecido",
  NOT_APPLICABLE: "Não se aplica",
  PASSED: "Passou",
  NOT_PASSED: "Não passou",
  FACIAL_RECOGNITION: "Reconhecimento facial",
  CARD: "Cartão",
  INFO: "Info",
  WARNING: "Atenção",
  CRITICAL: "Crítico",
  INVITED: "Convidado",
  PENDING_REGISTRATION: "Cadastro pendente",
  COMPLETED: "Completo",
  EXPIRED: "Expirado",
  CANCELLED: "Cancelado",
  SENT: "Enviado",
  ADMIN: "Admin",
  HR: "RH",
  SECURITY_VIEWER: "Segurança",
  SKIPPED: "Ignorado",
  FAILED: "Falhou",
  NOT_REQUIRED: "Não requer",
  PENDING_SYNC: "Pendente",
  SYNCING: "Sincronizando",
  SYNCED_WITH_WARNINGS: "Parcial",
  SYNCED: "Sincronizado",
  SYNC_FAILED: "Falhou",
  ENTRY_EXIT: "Entrada/Saída",
  SIMULATED: "Simulado",
  DEVICE: "Dispositivo"
};

export function humanizeStatus(value?: string | boolean | null) {
  if (typeof value === "boolean") return value ? "Ativo" : "Inativo";
  if (!value) return "Não informado";
  return labels[value] ?? value.replace(/_/g, " ").toLowerCase().replace(/^\w/, (letter) => letter.toUpperCase());
}

export function StatusBadge({ value }: { value?: string | boolean | null }) {
  const normalized = typeof value === "boolean" ? (value ? "ACTIVE" : "INACTIVE") : value;
  const tone =
    normalized === "ACTIVE" || normalized === "ONLINE" || normalized === "ALLOWED" || normalized === "SENT" || normalized === "SYNCED" || normalized === "RECOGNIZED" || normalized === "PASSED"
      ? "green"
      : normalized === "DENIED" || normalized === "BLOCKED" || normalized === "OFFLINE" || normalized === "ERROR" || normalized === "FAILED" || normalized === "SYNC_FAILED" || normalized === "NOT_RECOGNIZED" || normalized === "NOT_PASSED"
        ? "red"
        : normalized === "UNKNOWN" || normalized === "MAINTENANCE" || normalized === "MANUAL_ADMIN_RELEASE" || normalized === "SYNCED_WITH_WARNINGS"
          ? "amber"
          : normalized === "PENDING_SYNC" || normalized === "SYNCING"
            ? "amber"
          : normalized === "ENTRY" || normalized === "EXIT" || normalized === "ENTRY_EXIT"
            ? "blue"
            : "slate";

  return <Badge tone={tone}>{humanizeStatus(value)}</Badge>;
}
