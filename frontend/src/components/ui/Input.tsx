import clsx from "clsx";
import { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";

type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  error?: string;
};

export function Input({ label, error, className, id, ...props }: InputProps) {
  const inputId = id ?? props.name ?? label.toLowerCase().replace(/\s+/g, "-");
  return (
    <label htmlFor={inputId} className="block text-sm font-medium text-slate-300">
      <span>{label}</span>
      <input
        id={inputId}
        className={clsx(
          "mt-1 h-10 w-full rounded-xl border border-white/10 bg-white/[0.055] px-3 text-sm text-slate-100 shadow-sm outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]",
          error && "border-red-300/40 bg-red-500/10",
          className
        )}
        {...props}
      />
      {error ? <span className="mt-1 block text-xs font-medium text-red-200">{error}</span> : null}
    </label>
  );
}

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & {
  label: string;
  error?: string;
};

export function Select({ label, error, className, id, children, ...props }: SelectProps) {
  const inputId = id ?? props.name ?? label.toLowerCase().replace(/\s+/g, "-");
  return (
    <label htmlFor={inputId} className="block text-sm font-medium text-slate-300">
      <span>{label}</span>
      <select
        id={inputId}
        className={clsx(
          "mt-1 h-10 w-full rounded-xl border border-white/10 bg-white/[0.055] px-3 text-sm text-slate-100 shadow-sm outline-none transition focus:border-brand-wine focus:bg-white/[0.08]",
          error && "border-red-300/40 bg-red-500/10",
          className
        )}
        {...props}
      >
        {children}
      </select>
      {error ? <span className="mt-1 block text-xs font-medium text-red-200">{error}</span> : null}
    </label>
  );
}

type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  label: string;
};

export function Textarea({ label, className, id, ...props }: TextareaProps) {
  const inputId = id ?? props.name ?? label.toLowerCase().replace(/\s+/g, "-");
  return (
    <label htmlFor={inputId} className="block text-sm font-medium text-slate-300">
      <span>{label}</span>
      <textarea
        id={inputId}
        className={clsx("mt-1 min-h-24 w-full rounded-xl border border-white/10 bg-white/[0.055] px-3 py-2 text-sm text-slate-100 shadow-sm outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]", className)}
        {...props}
      />
    </label>
  );
}
