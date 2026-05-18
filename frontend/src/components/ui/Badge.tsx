import clsx from "clsx";
import { HTMLAttributes } from "react";

type BadgeTone = "slate" | "red" | "green" | "amber" | "blue";

const tones: Record<BadgeTone, string> = {
  slate: "border-slate-200 bg-slate-100 text-slate-700",
  red: "border-red-200 bg-red-50 text-red-700",
  green: "border-emerald-200 bg-emerald-50 text-emerald-700",
  amber: "border-amber-200 bg-amber-50 text-amber-800",
  blue: "border-sky-200 bg-sky-50 text-sky-700"
};

export function Badge({ tone = "slate", className, ...props }: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return (
    <span
      className={clsx("inline-flex h-7 items-center rounded-full border px-2.5 text-xs font-semibold", tones[tone], className)}
      {...props}
    />
  );
}
