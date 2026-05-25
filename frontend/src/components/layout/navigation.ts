import { Activity, AreaChart, Gauge, IdCard, ListChecks, MapPinned, MonitorCog, UserRoundPlus, type LucideIcon } from "lucide-react";
import { type Role } from "@/services/authService";

export type NavigationItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  roles: Role[];
};

export const navigationItems: NavigationItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/operations", label: "Operação ao vivo", icon: Activity, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/employees", label: "Colaboradores", icon: IdCard, roles: ["ADMIN", "HR"] },
  { href: "/guests", label: "Visitantes", icon: UserRoundPlus, roles: ["ADMIN", "HR"] },
  { href: "/devices", label: "Dispositivos", icon: MonitorCog, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/logs", label: "Logs de Eventos", icon: AreaChart, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/logsvisitante", label: "Consulta Entrada", icon: ListChecks, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/areas", label: "Áreas", icon: MapPinned, roles: ["ADMIN", "HR"] }
];
