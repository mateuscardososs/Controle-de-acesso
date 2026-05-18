"use client";

import { LogOut, Menu, UserRound } from "lucide-react";
import { Button } from "@/src/components/ui/Button";
import { Badge } from "@/src/components/ui/Badge";
import { type CurrentUser } from "@/services/authService";

export function Topbar({ user, onLogout, onMenu }: { user?: CurrentUser; onLogout: () => void; onMenu: () => void }) {
  return (
    <header className="sticky top-0 z-20 border-b border-slate-200/80 bg-white/90 backdrop-blur">
      <div className="flex h-16 items-center justify-between gap-3 px-4 lg:px-8">
        <div className="flex items-center gap-3">
          <Button aria-label="Abrir menu" variant="secondary" icon={Menu} className="h-10 w-10 px-0 lg:hidden" onClick={onMenu} />
          <div className="hidden sm:block">
            <p className="text-sm font-semibold text-slate-950">Central operacional</p>
            <p className="text-xs text-slate-500">Monitoramento administrativo de acesso</p>
          </div>
        </div>
        <div className="flex items-center gap-2 sm:gap-3">
          <div className="hidden items-center gap-3 rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 sm:flex">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-white text-slate-600 shadow-sm">
              <UserRound className="h-4 w-4" />
            </div>
            <div>
              <p className="max-w-40 truncate text-sm font-semibold text-slate-900">{user?.name ?? "Usuario"}</p>
              <p className="max-w-40 truncate text-xs text-slate-500">{user?.email}</p>
            </div>
            {user?.role ? <Badge tone="red">{user.role}</Badge> : null}
          </div>
          <Button variant="secondary" icon={LogOut} onClick={onLogout}>
            Sair
          </Button>
        </div>
      </div>
    </header>
  );
}
