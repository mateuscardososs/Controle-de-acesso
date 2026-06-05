"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowRight, CheckCircle2, ShieldCheck } from "lucide-react";
import type { AxiosError } from "axios";
import { apiErrorMessage } from "@/lib/errors";
import { formatCpfInput, isValidCpf } from "@/lib/cpf";
import { displayAreaName } from "@/lib/areaLabels";
import { configService } from "@/services/configService";
import { guestService } from "@/services/guestService";
import { CameraCapture } from "@/src/components/shared/CameraCapture";
import { Input, Select } from "@/src/components/ui/Input";
import { ErrorState } from "@/src/components/shared/AsyncState";

type ApiError = {
  error?: string;
  details?: string[];
};

const SUCCESS_MESSAGE = "Cadastro recebido com sucesso. Aguarde validação da organização.";

function localDate() {
  const date = new Date();
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 10);
}

export default function PublicVisitorRegistrationPage() {
  const lounges = useQuery({ queryKey: ["config", "lounges"], queryFn: configService.lounges });
  const [form, setForm] = useState({
    fullName: "",
    cpf: "",
    phone: "",
    email: "",
    invitedDay: localDate(),
    invitedLounge: ""
  });
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [message, setMessage] = useState("");
  const [cpfError, setCpfError] = useState("");

  const register = useMutation({
    mutationFn: () => {
      if (!facePhoto) throw new Error("Tire a foto pela câmera para continuar.");
      return (
      guestService.publicVisitorRegistration({
        ...form,
        facePhoto
      })
      );
    },
    onSuccess: () => setMessage(SUCCESS_MESSAGE),
    onError: (error) => setMessage(publicRegistrationErrorMessage(error))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isValidCpf(form.cpf)) {
      setCpfError("CPF inválido. Verifique os números informados.");
      setMessage("CPF inválido. Verifique os números informados.");
      return;
    }
    if (!facePhoto) {
      setMessage("Tire a foto pela câmera para continuar.");
      return;
    }
    setCpfError("");
    register.mutate();
  }

  return (
    <main className="flex min-h-screen flex-col bg-[#0B1020] px-4 py-5 sm:px-6">
      <section className="mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center py-6">
        <div className="mb-7 text-center">
          <div className="mx-auto mb-5 flex h-10 w-10 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.055] text-slate-100">
            <ShieldCheck className="h-5 w-5" />
          </div>
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
                onClick={() => {
                  register.reset();
                  setFacePhoto(null);
                  setMessage("");
                }}
              >
                Enviar outro cadastro
              </button>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4">
              <Input label="Nome completo" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
              <div className="grid gap-4 md:grid-cols-2">
                <Input
                  label="CPF"
                  value={form.cpf}
                  onChange={(event) => {
                    setForm({ ...form, cpf: formatCpfInput(event.target.value) });
                    setCpfError("");
                  }}
                  error={cpfError}
                  required
                  placeholder="000.000.000-00"
                  inputMode="numeric"
                />
                <Input label="Telefone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} required placeholder="(81) 99999-0000" />
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <Input label="E-mail (opcional)" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
                <Input label="Dia convidado" type="date" value={form.invitedDay} onChange={(event) => setForm({ ...form, invitedDay: event.target.value })} required />
              </div>
              <Select label="Camarote / Função" value={form.invitedLounge} onChange={(event) => setForm({ ...form, invitedLounge: event.target.value })} required>
                <option value="">Selecione</option>
                {(lounges.data ?? []).map((lounge) => <option key={lounge} value={lounge}>{displayAreaName(lounge)}</option>)}
              </Select>
              <div>
                <span className="mb-2 block text-sm font-medium text-slate-300">Foto facial</span>
                <CameraCapture value={facePhoto} onChange={setFacePhoto} disabled={register.isPending} />
              </div>
              {register.isError || message ? <ErrorState label={message} /> : null}
              <button
                type="submit"
                disabled={register.isPending}
                className="mt-1 inline-flex h-11 items-center justify-center gap-2 rounded-full bg-white px-5 text-sm font-semibold text-slate-950 transition hover:bg-slate-200 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {register.isPending ? "Finalizando..." : "Finalizar cadastro"}
                {!register.isPending ? <ArrowRight className="h-4 w-4" /> : null}
              </button>
            </form>
          )}
        </div>
      </section>

      <footer className="pb-2 text-center text-xs text-slate-700">Cadastro seguro</footer>
    </main>
  );
}

function publicRegistrationErrorMessage(error: unknown) {
  const axiosError = error as AxiosError<ApiError>;

  if (!axiosError.isAxiosError && error instanceof Error) {
    return error.message;
  }
  if (axiosError.code === "ERR_NETWORK" || !axiosError.response) {
    return "Conexão indisponível. Verifique sua internet e tente novamente.";
  }

  const details = axiosError.response.data?.details?.join(" ") ?? "";
  const rawMessage = `${axiosError.response.data?.error ?? ""} ${details}`.toLowerCase();

  if (axiosError.response.status === 409 || rawMessage.includes("conflict") || rawMessage.includes("duplicate")) {
    return "Cadastro duplicado. Já existe um cadastro com esses dados. Aguarde validação da organização.";
  }
  if (rawMessage.includes("cpf")) {
    return "CPF inválido. Verifique os números informados.";
  }
  if (axiosError.response.status === 429) {
    return "Muitas tentativas de cadastro. Aguarde um minuto e tente novamente.";
  }
  return apiErrorMessage(error, "Não foi possível enviar o cadastro. Revise os dados e tente novamente.");
}
