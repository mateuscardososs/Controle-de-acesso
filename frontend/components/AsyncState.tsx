import { AlertCircle, Inbox } from "lucide-react";

export function LoadingState({ label = "Carregando dados..." }: { label?: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-6 text-sm text-slate-600">
      {label}
    </div>
  );
}

export function ErrorState({ label = "Não foi possível carregar os dados." }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      <AlertCircle className="h-4 w-4" />
      {label}
    </div>
  );
}

export function EmptyState({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 rounded-md border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600">
      <Inbox className="h-4 w-4" />
      {label}
    </div>
  );
}
