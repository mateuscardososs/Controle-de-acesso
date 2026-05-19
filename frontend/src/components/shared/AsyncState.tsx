import { AlertCircle, Inbox, Loader2 } from "lucide-react";
import { Card } from "@/src/components/ui/Card";

export function LoadingState({ label = "Carregando dados..." }: { label?: string }) {
  return (
    <Card className="flex items-center gap-3 p-6 text-sm font-medium text-slate-300">
      <Loader2 className="h-4 w-4 animate-spin text-brand-wine" />
      {label}
    </Card>
  );
}

export function ErrorState({ label = "Não foi possível carregar os dados." }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-red-300/20 bg-red-500/12 p-4 text-sm font-semibold text-red-100 shadow-sm">
      <AlertCircle className="h-4 w-4" />
      {label}
    </div>
  );
}

export function EmptyState({ label, description }: { label: string; description?: string }) {
  return (
    <Card className="flex flex-col items-center justify-center gap-2 border-dashed border-white/15 p-10 text-center">
      <div className="flex h-11 w-11 items-center justify-center rounded-full border border-white/10 bg-white/[0.06] text-slate-400">
        <Inbox className="h-5 w-5" />
      </div>
      <p className="text-sm font-semibold text-slate-100">{label}</p>
      {description ? <p className="max-w-md text-sm text-slate-400">{description}</p> : null}
    </Card>
  );
}
