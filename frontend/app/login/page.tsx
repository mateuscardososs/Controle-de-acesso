"use client";

import { authService } from "@/services/authService";
import { useMutation } from "@tanstack/react-query";
import { AlertCircle, LockKeyhole, Mail, ShieldCheck } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { Button } from "@/src/components/ui/Button";
import { Card } from "@/src/components/ui/Card";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const login = useMutation({
    mutationFn: () => authService.login(email, password),
    onSuccess: () => router.replace("/dashboard")
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    login.mutate();
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-[1.05fr_0.95fr]">
      <section className="relative hidden overflow-hidden border-r border-white/10 bg-[#070B15] px-12 py-10 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(154,27,46,0.46),transparent_34rem)]" />
        <div className="absolute inset-x-12 bottom-28 h-px bg-gradient-to-r from-transparent via-white/20 to-transparent" />
        <div className="relative flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-red-200/15 bg-gradient-to-b from-brand-wine to-brand-maroon shadow-xl shadow-red-950/30">
            <ShieldCheck className="h-7 w-7" />
          </div>
          <div>
            <p className="text-sm font-semibold">Controle de Acesso</p>
            <p className="text-xs text-slate-400">Plataforma multiempresa</p>
          </div>
        </div>
        <div className="relative max-w-xl">
          <p className="text-sm font-bold uppercase tracking-[0.18em] text-red-200">Central operacional</p>
          <h1 className="mt-4 text-5xl font-semibold tracking-tight">Controle institucional de acessos, pessoas e dispositivos.</h1>
          <p className="mt-5 text-base leading-7 text-slate-300">
            Interface administrativa para monitorar catracas, colaboradores, areas e eventos com seguranca operacional.
          </p>
        </div>
        <div className="relative grid grid-cols-3 gap-3 text-sm">
          {["JWT/RBAC", "Dispositivos", "Eventos"].map((item) => (
            <div key={item} className="rounded-2xl border border-white/10 bg-white/[0.055] p-4 text-slate-200 backdrop-blur">
              {item}
            </div>
          ))}
        </div>
      </section>

      <section className="flex items-center justify-center px-4 py-10">
        <Card className="w-full max-w-md p-6 sm:p-8">
          <form onSubmit={submit}>
            <div className="mb-8 text-center">
              <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl border border-red-300/20 bg-red-500/12 text-red-200">
                <LockKeyhole className="h-6 w-6" />
              </div>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-50">Controle de Acesso</h1>
              <p className="mt-1 text-sm text-slate-500">Plataforma multiempresa</p>
            </div>

            <label htmlFor="email" className="mb-4 block text-sm font-medium text-slate-300">
              Email
              <div className="relative mt-1">
                <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  id="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  className="h-11 w-full rounded-xl border border-white/10 bg-white/[0.055] pl-10 pr-3 text-sm text-slate-100 outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]"
                  type="email"
                  autoComplete="email"
                  placeholder="admin@empresa.local"
                />
              </div>
            </label>

            <label htmlFor="password" className="mb-4 block text-sm font-medium text-slate-300">
              Senha
              <div className="relative mt-1">
                <LockKeyhole className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  id="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  className="h-11 w-full rounded-xl border border-white/10 bg-white/[0.055] pl-10 pr-3 text-sm text-slate-100 outline-none transition placeholder:text-slate-500 focus:border-brand-wine focus:bg-white/[0.08]"
                  type="password"
                  autoComplete="current-password"
                  placeholder="Sua senha"
                />
              </div>
            </label>

            {login.isError ? (
              <div className="mb-4 flex items-center gap-2 rounded-xl border border-red-300/20 bg-red-500/12 px-3 py-2 text-sm font-medium text-red-100">
                <AlertCircle className="h-4 w-4" />
                Credenciais invalidas ou sessao indisponivel.
              </div>
            ) : null}

            <Button type="submit" loading={login.isPending} className="h-11 w-full">
              Entrar
            </Button>
          </form>
        </Card>
      </section>
    </main>
  );
}
