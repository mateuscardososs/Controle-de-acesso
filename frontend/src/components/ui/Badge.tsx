import clsx from "clsx";
import { HTMLAttributes } from "react";

type BadgeTone = "slate" | "red" | "green" | "amber" | "blue";

const tones: Record<BadgeTone, string> = {
  slate: "border-white/10 bg-white/[0.06] text-slate-300",
  red: "border-red-300/20 bg-red-500/12 text-red-200",
  green: "border-emerald-300/20 bg-emerald-400/12 text-emerald-200",
  amber: "border-amber-300/20 bg-amber-400/12 text-amber-200",
  blue: "border-sky-300/20 bg-sky-400/12 text-sky-200"
};

export function Badge({ tone = "slate", className, ...props }: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return (
    <span
      className={clsx("inline-flex h-7 items-center rounded-full border px-2.5 text-xs font-semibold shadow-sm backdrop-blur", tones[tone], className)}
      {...props}
    />
  );
}
