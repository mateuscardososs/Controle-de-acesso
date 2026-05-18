import { AlertCircle, Inbox, Loader2 } from "lucide-react";
import { Card } from "@/src/components/ui/Card";

export function LoadingState({ label = "Carregando dados..." }: { label?: string }) {
  return (
    <Card className="flex items-center gap-3 p-6 text-sm font-medium text-slate-600">
      <Loader2 className="h-4 w-4 animate-spin text-sport-red" />
      {label}
    </Card>
  );
}

export function ErrorState({ label = "Não foi possível carregar os dados." }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm font-semibold text-red-800 shadow-sm">
      <AlertCircle className="h-4 w-4" />
      {label}
    </div>
  );
}

export function EmptyState({ label, description }: { label: string; description?: string }) {
  return (
    <Card className="flex flex-col items-center justify-center gap-2 border-dashed p-10 text-center">
      <div className="flex h-11 w-11 items-center justify-center rounded-full bg-slate-100 text-slate-500">
        <Inbox className="h-5 w-5" />
      </div>
      <p className="text-sm font-semibold text-slate-800">{label}</p>
      {description ? <p className="max-w-md text-sm text-slate-500">{description}</p> : null}
    </Card>
  );
}
