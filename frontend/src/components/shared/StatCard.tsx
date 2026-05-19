import { ArrowUpRight, type LucideIcon } from "lucide-react";
import { Card } from "@/src/components/ui/Card";

export function StatCard({ title, value, icon: Icon, description }: { title: string; value: number | string; icon: LucideIcon; description?: string }) {
  return (
    <Card className="group relative overflow-hidden p-5">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-brand-wine/60 to-transparent opacity-80" />
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-slate-400">{title}</p>
          <p className="mt-3 text-3xl font-semibold tracking-tight text-slate-50">{value}</p>
        </div>
        <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-white/10 bg-white/[0.06] text-brand-wine transition group-hover:border-brand-wine/30 group-hover:bg-brand-wine/10">
          <Icon className="h-5 w-5" />
        </div>
      </div>
      <div className="mt-4 flex items-center justify-between gap-3">
        {description ? <p className="text-xs font-medium text-slate-500">{description}</p> : <span />}
        <ArrowUpRight className="h-4 w-4 text-slate-600 transition group-hover:text-brand-wine" />
      </div>
    </Card>
  );
}
