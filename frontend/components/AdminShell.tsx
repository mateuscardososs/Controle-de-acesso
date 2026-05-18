"use client";

import { authService } from "@/services/authService";
import { useQuery } from "@tanstack/react-query";
import { AreaChart, Gauge, IdCard, LogOut, MapPinned, MonitorCog, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect, useMemo } from "react";
import { TOKEN_KEY } from "@/lib/api";
import clsx from "clsx";

const items = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/employees", label: "Colaboradores", icon: IdCard, roles: ["ADMIN", "HR"] },
  { href: "/devices", label: "Dispositivos", icon: MonitorCog, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/access-events", label: "Eventos", icon: AreaChart, roles: ["ADMIN", "HR", "SECURITY_VIEWER"] },
  { href: "/areas", label: "Áreas", icon: MapPinned, roles: ["ADMIN", "HR"] }
];

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { data: user, isError, isLoading } = useQuery({
    queryKey: ["me"],
    queryFn: authService.me,
    retry: false
  });
  const visibleItems = useMemo(
    () => items.filter((item) => user?.role && item.roles.includes(user.role)),
    [user?.role]
  );

  useEffect(() => {
    if (typeof window !== "undefined" && !window.localStorage.getItem(TOKEN_KEY)) {
      router.replace("/login");
    }
  }, [router]);

  useEffect(() => {
    if (isError) {
      authService.logout();
    }
  }, [isError]);

  if (isLoading) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-slate-600">Carregando...</div>;
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-slate-200 bg-white lg:block">
        <div className="flex h-16 items-center gap-3 border-b border-slate-200 px-5">
          <ShieldCheck className="h-7 w-7 text-sport-red" />
          <div>
            <p className="text-sm font-semibold text-slate-900">Access Control</p>
            <p className="text-xs text-slate-500">{user?.role}</p>
          </div>
        </div>
        <nav className="space-y-1 p-3">
          {visibleItems.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={clsx(
                  "flex h-10 items-center gap-3 rounded-md px-3 text-sm font-medium",
                  active ? "bg-red-50 text-sport-red" : "text-slate-700 hover:bg-slate-100"
                )}
              >
                <Icon className="h-4 w-4" />
                {item.label}
              </Link>
            );
          })}
        </nav>
      </aside>
      <main className="lg:pl-64">
        <header className="sticky top-0 z-10 flex h-16 items-center justify-between border-b border-slate-200 bg-white px-4 lg:px-8">
          <div>
            <p className="text-sm font-medium text-slate-900">{user?.name}</p>
            <p className="text-xs text-slate-500">{user?.email}</p>
          </div>
          <button
            onClick={authService.logout}
            className="inline-flex h-9 items-center gap-2 rounded-md border border-slate-300 px-3 text-sm text-slate-700 hover:bg-slate-100"
          >
            <LogOut className="h-4 w-4" />
            Sair
          </button>
        </header>
        <div className="p-4 lg:p-8">{children}</div>
      </main>
    </div>
  );
}
