import clsx from "clsx";
import { HTMLAttributes } from "react";

type BadgeTone = "slate" | "red" | "green" | "amber" | "blue";

const tones: Record<BadgeTone, string> = {
  slate: "border-white/15 bg-white/[0.09] text-slate-200",
  red: "border-red-300/30 bg-red-500/18 text-red-100",
  green: "border-emerald-300/30 bg-emerald-400/18 text-emerald-100",
  amber: "border-amber-300/30 bg-amber-400/18 text-amber-100",
  blue: "border-sky-300/30 bg-sky-400/18 text-sky-100"
};

export function Badge({ tone = "slate", className, ...props }: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return (
    <span
      className={clsx("inline-flex h-7 items-center rounded-full border px-2.5 text-xs font-semibold shadow-sm backdrop-blur", tones[tone], className)}
      {...props}
    />
  );
}
