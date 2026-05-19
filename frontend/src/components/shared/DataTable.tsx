import { ReactNode } from "react";
import { Card } from "@/src/components/ui/Card";
import clsx from "clsx";

export type DataTableColumn<T> = {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  className?: string;
};

export function DataTable<T>({ data, columns, getRowKey }: { data: T[]; columns: DataTableColumn<T>[]; getRowKey: (row: T) => string }) {
  return (
    <Card className="overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-left text-sm">
          <thead className="sticky top-0 z-10 border-b border-white/10 bg-white/[0.045] text-xs uppercase tracking-[0.16em] text-slate-400 backdrop-blur">
            <tr>
              {columns.map((column) => (
                <th key={column.key} className="px-4 py-3 font-semibold">
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-white/10">
            {data.map((row) => (
              <tr key={getRowKey(row)} className="transition hover:bg-white/[0.045]">
                {columns.map((column) => (
                  <td key={column.key} className={clsx("px-4 py-3 align-middle text-slate-300", column.className)}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
