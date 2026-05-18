import { LucideIcon } from "lucide-react";

export function StatCard({ title, value, icon: Icon }: { title: string; value: number | string; icon: LucideIcon }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-5">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium text-slate-600">{title}</p>
        <Icon className="h-5 w-5 text-slate-500" />
      </div>
      <p className="mt-4 text-3xl font-semibold text-slate-950">{value}</p>
    </div>
  );
}
