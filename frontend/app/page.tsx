"use client";

import Link from "next/link";
import { ChangeEvent, FormEvent, useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowRight, CheckCircle2, LockKeyhole, ShieldCheck, UploadCloud, X } from "lucide-react";
import { apiErrorMessage } from "@/lib/errors";
import { guestService } from "@/services/guestService";
import { Input } from "@/src/components/ui/Input";
import { ErrorState } from "@/src/components/shared/AsyncState";

function localDateTime(daysFromNow = 0) {
  const date = new Date();
  date.setDate(date.getDate() + daysFromNow);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
}

export default function PublicVisitorRegistrationPage() {
  const [form, setForm] = useState({
    fullName: "",
    cpf: "",
    email: "",
    phone: "",
    company: "",
    visitReason: "",
    hostName: "",
    visitStart: localDateTime(),
    visitEnd: localDateTime(1)
  });
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [message, setMessage] = useState("");
  const preview = useMemo(() => (facePhoto ? URL.createObjectURL(facePhoto) : ""), [facePhoto]);

  const register = useMutation({
    mutationFn: () =>
      guestService.publicVisitorRegistration({
        ...form,
        visitStart: new Date(form.visitStart).toISOString(),
        visitEnd: new Date(form.visitEnd).toISOString(),
        facePhoto
      }),
    onSuccess: (response) => setMessage(response.message),
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível enviar o cadastro. Revise os dados e tente novamente."))
  });

  function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) setFacePhoto(file);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    register.mutate();
  }

  return (
    <main className="min-h-screen bg-[#0B1020] px-4 py-4 sm:px-6">
      <header className="mx-auto flex w-full max-w-3xl items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl border border-white/10 bg-white/[0.055] text-slate-100">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <p className="text-sm font-semibold text-slate-100">Controle de Acesso</p>
        </div>
        <Link href="/login" className="inline-flex h-9 items-center justify-center gap-2 rounded-full border border-white/10 bg-white/[0.04] px-3 text-xs font-semibold text-slate-200 transition hover:border-white/20 hover:bg-white/[0.08] sm:px-4 sm:text-sm">
          <LockKeyhole className="h-4 w-4" />
          <span className="hidden sm:inline">Entrar como administrador</span>
          <span className="sm:hidden">Admin</span>
        </Link>
      </header>

      <section className="mx-auto flex w-full max-w-3xl flex-col items-center pb-8 pt-8 sm:pt-12">
        <div className="mb-7 text-center">
          <h1 className="text-3xl font-semibold tracking-tight text-slate-50 sm:text-5xl">Cadastro de visitante</h1>
          <p className="mt-3 text-sm text-slate-400 sm:text-base">Preencha seus dados para solicitar acesso.</p>
        </div>

        <div className="w-full rounded-[28px] border border-white/10 bg-white/[0.04] p-4 shadow-[0_18px_70px_rgba(0,0,0,0.22)] backdrop-blur-xl sm:p-6">
          {register.isSuccess ? (
            <div className="flex flex-col items-center justify-center px-3 py-10 text-center">
              <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full border border-emerald-300/20 bg-emerald-400/12 text-emerald-200">
                <CheckCircle2 className="h-6 w-6" />
              </div>
              <h2 className="text-lg font-semibold text-slate-50">Cadastro enviado</h2>
              <p className="mt-2 max-w-sm text-sm leading-6 text-slate-400">{message}</p>
              <button
                type="button"
                className="mt-6 h-10 rounded-full bg-white px-5 text-sm font-semibold text-slate-950 transition hover:bg-slate-200"
                onClick={() => register.reset()}
              >
                Enviar outro cadastro
              </button>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4">
              <Input label="Nome completo" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
              <div className="grid gap-4 md:grid-cols-2">
                <Input label="CPF" value={form.cpf} onChange={(event) => setForm({ ...form, cpf: event.target.value })} required placeholder="00000000000" />
                <Input label="E-mail" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} required />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <Input label="Telefone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
                <Input label="Empresa" value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <Input label="Responsável/host" value={form.hostName} onChange={(event) => setForm({ ...form, hostName: event.target.value })} required />
                <Input label="Motivo da visita" value={form.visitReason} onChange={(event) => setForm({ ...form, visitReason: event.target.value })} required />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <Input label="Início" type="datetime-local" value={form.visitStart} onChange={(event) => setForm({ ...form, visitStart: event.target.value })} required />
                <Input label="Fim" type="datetime-local" value={form.visitEnd} onChange={(event) => setForm({ ...form, visitEnd: event.target.value })} required />
              </div>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-300">Foto facial</span>
                <div className="flex min-h-28 cursor-pointer items-center justify-center rounded-2xl border border-dashed border-white/15 bg-white/[0.035] p-3 transition hover:border-white/25 hover:bg-white/[0.055]">
                  <input className="sr-only" type="file" accept="image/png,image/jpeg,image/webp" onChange={onFile} />
                  {preview ? (
                    <div className="flex w-full items-center gap-3">
                      <img src={preview} alt="Preview da foto facial" className="h-16 w-16 rounded-2xl object-cover" />
                      <div className="min-w-0 flex-1 text-left">
                        <p className="truncate text-sm font-semibold text-slate-100">{facePhoto?.name}</p>
                        <p className="text-xs text-slate-500">Foto anexada</p>
                      </div>
                      <button
                        type="button"
                        aria-label="Remover foto"
                        className="flex h-8 w-8 items-center justify-center rounded-full border border-white/10 text-slate-400 transition hover:bg-white/10 hover:text-white"
                        onClick={(event) => {
                          event.preventDefault();
                          setFacePhoto(null);
                        }}
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-3 text-left">
                      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-white/[0.06] text-slate-400">
                        <UploadCloud className="h-5 w-5" />
                      </div>
                      <div>
                        <p className="text-sm font-semibold text-slate-100">Adicionar foto</p>
                        <p className="text-xs text-slate-500">PNG, JPG ou WEBP</p>
                      </div>
                    </div>
                  )}
                </div>
              </label>
              {register.isError ? <ErrorState label={message} /> : null}
              <button
                type="submit"
                disabled={register.isPending}
                className="mt-1 inline-flex h-11 items-center justify-center gap-2 rounded-full bg-white px-5 text-sm font-semibold text-slate-950 transition hover:bg-slate-200 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {register.isPending ? "Enviando..." : "Enviar cadastro"}
                {!register.isPending ? <ArrowRight className="h-4 w-4" /> : null}
              </button>
            </form>
          )}
        </div>
      </section>

      <footer className="pb-5 text-center text-xs text-slate-600">Controle de Acesso</footer>
    </main>
  );
}
