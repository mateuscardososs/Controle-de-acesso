import { type LucideIcon } from "lucide-react";
import { Card } from "@/src/components/ui/Card";

export function StatCard({ title, value, icon: Icon, description }: { title: string; value: number | string; icon: LucideIcon; description?: string }) {
  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-slate-500">{title}</p>
          <p className="mt-3 text-3xl font-semibold tracking-tight text-slate-950">{value}</p>
        </div>
        <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-red-50 text-sport-red">
          <Icon className="h-5 w-5" />
        </div>
      </div>
      {description ? <p className="mt-4 text-xs font-medium text-slate-500">{description}</p> : null}
    </Card>
  );
}
