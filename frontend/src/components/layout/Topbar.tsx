"use client";

import { LogOut, Menu, Search, UserRound } from "lucide-react";
import { Button } from "@/src/components/ui/Button";
import { Badge } from "@/src/components/ui/Badge";
import { type CurrentUser } from "@/services/authService";

export function Topbar({ user, onLogout, onMenu }: { user?: CurrentUser; onLogout: () => void; onMenu: () => void }) {
  return (
    <header className="sticky top-0 z-20 border-b border-white/10 bg-[#0B1020]/72 backdrop-blur-2xl">
      <div className="flex h-16 items-center justify-between gap-3 px-4 lg:px-8">
        <div className="flex items-center gap-3">
          <Button aria-label="Abrir menu" variant="secondary" icon={Menu} className="h-10 w-10 px-0 lg:hidden" onClick={onMenu} />
          <div className="hidden sm:block">
            <p className="text-sm font-semibold text-slate-100">Central operacional</p>
            <p className="text-xs text-slate-500">Monitoramento administrativo de acesso</p>
          </div>
        </div>
        <div className="hidden min-w-0 flex-1 justify-center px-6 md:flex">
          <div className="flex h-10 w-full max-w-md items-center gap-2 rounded-full border border-white/10 bg-white/[0.045] px-3 text-sm text-slate-500 shadow-sm">
            <Search className="h-4 w-4" />
            <span className="truncate">Buscar pessoas, dispositivos, eventos...</span>
            <kbd className="ml-auto rounded-md border border-white/10 bg-white/[0.05] px-1.5 py-0.5 text-[10px] text-slate-500">⌘K</kbd>
          </div>
        </div>
        <div className="flex items-center gap-2 sm:gap-3">
          <Badge tone="green" className="hidden gap-2 md:inline-flex">
            <span className="h-2 w-2 rounded-full bg-emerald-400 shadow-[0_0_18px_rgba(52,211,153,0.8)]" />
            Realtime
          </Badge>
          <div className="hidden items-center gap-3 rounded-full border border-white/10 bg-white/[0.045] px-3 py-1.5 sm:flex">
            <div className="flex h-8 w-8 items-center justify-center rounded-full border border-white/10 bg-white/[0.06] text-slate-300 shadow-sm">
              <UserRound className="h-4 w-4" />
            </div>
            <div>
              <p className="max-w-40 truncate text-sm font-semibold text-slate-100">{user?.name ?? "Usuario"}</p>
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
