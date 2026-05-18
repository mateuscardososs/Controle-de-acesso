"use client";

import { ShieldCheck, X } from "lucide-react";
import Link from "next/link";
import clsx from "clsx";
import { Button } from "@/src/components/ui/Button";
import { type NavigationItem } from "./navigation";

export function Sidebar({
  items,
  pathname,
  role,
  open,
  onClose
}: {
  items: NavigationItem[];
  pathname: string;
  role?: string;
  open?: boolean;
  onClose?: () => void;
}) {
  const content = (
    <div className="flex h-full flex-col bg-slate-950 text-white">
      <div className="flex h-20 items-center justify-between border-b border-white/10 px-5">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-sport-red text-white shadow-lg shadow-red-950/25">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <div>
            <p className="text-sm font-semibold">Controle de Acesso</p>
            <p className="text-xs text-slate-400">Sport Club do Recife</p>
          </div>
        </div>
        {onClose ? <Button aria-label="Fechar menu" variant="ghost" icon={X} className="h-9 w-9 px-0 text-white hover:bg-white/10 lg:hidden" onClick={onClose} /> : null}
      </div>
      <nav className="flex-1 space-y-1 px-3 py-5">
        {items.map((item) => {
          const Icon = item.icon;
          const active = pathname === item.href;
          return (
            <Link
              key={item.href}
              href={item.href}
              onClick={onClose}
              className={clsx(
                "flex h-11 items-center gap-3 rounded-lg px-3 text-sm font-semibold transition",
                active ? "bg-white text-slate-950 shadow-sm" : "text-slate-300 hover:bg-white/10 hover:text-white"
              )}
            >
              <Icon className={clsx("h-4 w-4", active ? "text-sport-red" : "text-slate-400")} />
              {item.label}
            </Link>
          );
        })}
      </nav>
      <div className="border-t border-white/10 p-4">
        <p className="text-xs uppercase tracking-wide text-slate-500">Perfil operacional</p>
        <p className="mt-1 text-sm font-semibold text-slate-200">{role ?? "Autenticado"}</p>
      </div>
    </div>
  );

  return (
    <>
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-72 lg:block">{content}</aside>
      <div className={clsx("fixed inset-0 z-40 lg:hidden", open ? "block" : "hidden")}>
        <button aria-label="Fechar menu" className="absolute inset-0 bg-slate-950/50" onClick={onClose} />
        <aside className="relative h-full w-[min(18rem,85vw)] shadow-2xl">{content}</aside>
      </div>
    </>
  );
}
