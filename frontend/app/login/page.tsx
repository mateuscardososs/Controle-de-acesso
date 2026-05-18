"use client";

import { authService } from "@/services/authService";
import { useMutation } from "@tanstack/react-query";
import { LockKeyhole } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("admin@sport.local");
  const [password, setPassword] = useState("Admin@123456");
  const login = useMutation({
    mutationFn: () => authService.login(email, password),
    onSuccess: () => router.replace("/dashboard")
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    login.mutate();
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <form onSubmit={submit} className="w-full max-w-sm rounded-md border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-red-50 text-sport-red">
            <LockKeyhole className="h-5 w-5" />
          </div>
          <div>
            <h1 className="text-lg font-semibold text-slate-950">Painel Administrativo</h1>
            <p className="text-sm text-slate-500">Controle de acesso Sport</p>
          </div>
        </div>
        <label className="mb-4 block text-sm font-medium text-slate-700">
          Email
          <input
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="mt-1 h-10 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-sport-red"
            type="email"
          />
        </label>
        <label className="mb-4 block text-sm font-medium text-slate-700">
          Senha
          <input
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="mt-1 h-10 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-sport-red"
            type="password"
          />
        </label>
        {login.isError ? <p className="mb-4 text-sm text-red-700">Credenciais inválidas.</p> : null}
        <button
          disabled={login.isPending}
          className="h-10 w-full rounded-md bg-sport-red px-4 text-sm font-semibold text-white hover:bg-red-800 disabled:opacity-60"
        >
          {login.isPending ? "Entrando..." : "Entrar"}
        </button>
      </form>
    </main>
  );
}
