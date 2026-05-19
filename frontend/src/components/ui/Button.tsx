import { Loader2, type LucideIcon } from "lucide-react";
import clsx from "clsx";
import { ButtonHTMLAttributes } from "react";

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  loading?: boolean;
  icon?: LucideIcon;
};

const variants: Record<ButtonVariant, string> = {
  primary: "border border-red-300/20 bg-gradient-to-b from-brand-wine to-brand-maroon text-white shadow-lg shadow-red-950/20 hover:brightness-110",
  secondary: "border border-white/10 bg-white/[0.055] text-slate-100 shadow-sm hover:border-white/20 hover:bg-white/[0.09]",
  ghost: "text-slate-300 hover:bg-white/[0.07] hover:text-white",
  danger: "border border-red-300/20 bg-red-500/16 text-red-100 shadow-sm hover:bg-red-500/24"
};

export function Button({ variant = "primary", loading, icon: Icon, className, children, disabled, type = "button", ...props }: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={clsx(
        "inline-flex h-10 items-center justify-center gap-2 rounded-xl px-4 text-sm font-semibold transition duration-200 disabled:cursor-not-allowed disabled:opacity-50",
        variants[variant],
        className
      )}
      {...props}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : Icon ? <Icon className="h-4 w-4" /> : null}
      {children}
    </button>
  );
}
