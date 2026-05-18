import { ReactNode } from "react";
import { Card } from "@/src/components/ui/Card";

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
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              {columns.map((column) => (
                <th key={column.key} className="px-4 py-3 font-semibold">
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {data.map((row) => (
              <tr key={getRowKey(row)} className="bg-white transition hover:bg-slate-50/70">
                {columns.map((column) => (
                  <td key={column.key} className={column.className ?? "px-4 py-3 align-middle text-slate-700"}>
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
