"use client";

import { X } from "lucide-react";
import { ReactNode } from "react";
import { motion } from "framer-motion";
import { Button } from "./Button";

export function Modal({ title, description, open, onClose, children }: { title: string; description?: string; open: boolean; onClose: () => void; children: ReactNode }) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-950/70 p-0 backdrop-blur-md sm:items-center sm:p-4">
      <motion.div
        initial={{ opacity: 0, y: 18, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.18, ease: "easeOut" }}
        className="enterprise-surface max-h-[92vh] w-full max-w-3xl overflow-hidden rounded-t-2xl shadow-enterprise sm:rounded-2xl"
      >
        <div className="flex items-start justify-between gap-4 border-b border-white/10 px-5 py-4">
          <div>
            <h2 className="text-base font-semibold text-slate-50">{title}</h2>
            {description ? <p className="mt-1 text-sm text-slate-400">{description}</p> : null}
          </div>
          <Button aria-label="Fechar" variant="ghost" icon={X} className="h-9 w-9 px-0" onClick={onClose} />
        </div>
        <div className="max-h-[calc(92vh-88px)] overflow-y-auto p-5">{children}</div>
      </motion.div>
    </div>
  );
}
