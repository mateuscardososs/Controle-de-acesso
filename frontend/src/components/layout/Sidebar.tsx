"use client";

import { ChevronsLeft, ChevronsRight, ShieldCheck, X } from "lucide-react";
import Link from "next/link";
import clsx from "clsx";
import { motion } from "framer-motion";
import { Button } from "@/src/components/ui/Button";
import { type NavigationItem } from "./navigation";

export function Sidebar({
  items,
  pathname,
  role,
  open,
  onClose,
  collapsed,
  onToggleCollapsed
}: {
  items: NavigationItem[];
  pathname: string;
  role?: string;
  open?: boolean;
  onClose?: () => void;
  collapsed?: boolean;
  onToggleCollapsed?: () => void;
}) {
  const renderContent = (isCollapsed: boolean) => (
    <div className="flex h-full flex-col border-r border-white/10 bg-[#080D19]/86 text-white shadow-enterprise backdrop-blur-2xl">
      <div className={clsx("flex h-20 items-center justify-between border-b border-white/10 px-4", isCollapsed && "justify-center px-3")}>
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl border border-red-200/15 bg-gradient-to-b from-brand-wine to-brand-maroon text-white shadow-lg shadow-red-950/30">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <div className={clsx("transition", isCollapsed && "hidden")}>
            <p className="text-sm font-semibold">Controle de Acesso</p>
            <p className="text-xs text-slate-400">Plataforma multiempresa</p>
          </div>
        </div>
        {onClose ? <Button aria-label="Fechar menu" variant="ghost" icon={X} className="h-9 w-9 px-0 text-white lg:hidden" onClick={onClose} /> : null}
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
                "group relative flex h-11 items-center gap-3 rounded-xl px-3 text-sm font-semibold transition duration-200",
                active ? "bg-white/[0.09] text-white shadow-sm ring-1 ring-white/10" : "text-slate-400 hover:bg-white/[0.055] hover:text-white",
                isCollapsed && "justify-center px-0"
              )}
              title={isCollapsed ? item.label : undefined}
            >
              {active ? <motion.span layoutId="active-nav" className="absolute inset-y-2 left-0 w-1 rounded-r-full bg-brand-wine" /> : null}
              <Icon className={clsx("relative h-4 w-4", active ? "text-brand-wine" : "text-slate-500 group-hover:text-slate-200")} />
              <span className={clsx("relative", isCollapsed && "hidden")}>{item.label}</span>
            </Link>
          );
        })}
      </nav>
      <div className="space-y-3 border-t border-white/10 p-3">
        {onToggleCollapsed ? (
          <Button
            aria-label={collapsed ? "Expandir sidebar" : "Recolher sidebar"}
            variant="ghost"
            icon={isCollapsed ? ChevronsRight : ChevronsLeft}
            className="hidden h-9 w-full justify-center px-0 lg:flex"
            onClick={onToggleCollapsed}
          />
        ) : null}
        <div className={clsx("rounded-2xl border border-white/10 bg-white/[0.04] p-3", isCollapsed && "px-2 text-center")}>
          <p className={clsx("text-xs uppercase tracking-[0.16em] text-slate-500", isCollapsed && "hidden")}>Perfil operacional</p>
          <p className="mt-1 truncate text-sm font-semibold text-slate-200">{role ?? "Auth"}</p>
        </div>
      </div>
    </div>
  );

  return (
    <>
      <aside className={clsx("fixed inset-y-0 left-0 z-30 hidden transition-all duration-300 lg:block", collapsed ? "w-24" : "w-72")}>{renderContent(Boolean(collapsed))}</aside>
      <div className={clsx("fixed inset-0 z-40 lg:hidden", open ? "block" : "hidden")}>
        <button aria-label="Fechar menu" className="absolute inset-0 bg-slate-950/50" onClick={onClose} />
        <aside className="relative h-full w-[min(18rem,85vw)] shadow-2xl">{renderContent(false)}</aside>
      </div>
    </>
  );
}
