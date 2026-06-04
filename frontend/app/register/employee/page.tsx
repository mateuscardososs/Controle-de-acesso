"use client";

import { FormEvent, useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { AlertTriangle, ArrowRight, CheckCircle2, ShieldCheck, UserPlus } from "lucide-react";
import { apiErrorMessage } from "@/lib/errors";
import { formatCpfInput, isValidCpf, onlyCpfDigits } from "@/lib/cpf";
import { employeeService } from "@/services/employeeService";
import { CameraCapture } from "@/src/components/shared/CameraCapture";
import { ErrorState } from "@/src/components/shared/AsyncState";
import { Button } from "@/src/components/ui/Button";
import { Input } from "@/src/components/ui/Input";

type CpfStatus = "idle" | "checking" | "available" | "duplicate" | "invalid" | "error";

export default function PublicEmployeeRegistrationPage() {
  const [form, setForm] = useState({
    fullName: "",
    cpf: "",
    phone: "",
    email: ""
  });
  const [cpfStatus, setCpfStatus] = useState<CpfStatus>("idle");
  const [cpfMessage, setCpfMessage] = useState("");
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [message, setMessage] = useState("");

  const register = useMutation({
    mutationFn: () => {
      if (!facePhoto) throw new Error("Tire a foto pela câmera para continuar.");
      return employeeService.publicRegister({
        ...form,
        cpf: onlyCpfDigits(form.cpf),
        phone: form.phone.trim(),
        email: form.email.trim(),
        facePhoto
      });
    },
    onSuccess: () => setMessage("Cadastro realizado! Seu acesso é válido por 45 dias."),
    onError: (error) => setMessage(publicEmployeeRegistrationErrorMessage(error))
  });

  useEffect(() => {
    const digits = onlyCpfDigits(form.cpf);
    if (digits.length !== 11 || !isValidCpf(digits)) {
      return;
    }

    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setCpfStatus("checking");
      employeeService.checkPublicCpf(digits, controller.signal)
        .then((response) => {
          if (response.registered) {
            setCpfStatus("duplicate");
            setCpfMessage("CPF já cadastrado.");
            return;
          }
          setCpfStatus("available");
          setCpfMessage("CPF disponível.");
        })
        .catch((error) => {
          if (error instanceof Error && error.name === "CanceledError") return;
          setCpfStatus("error");
          setCpfMessage("Não foi possível validar o CPF agora.");
        });
    }, 500);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [form.cpf]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    if (!isValidCpf(form.cpf)) {
      setCpfStatus("invalid");
      setCpfMessage("CPF inválido. Verifique os números informados.");
      return;
    }
    if (cpfStatus === "duplicate") {
      setMessage("CPF já cadastrado.");
      return;
    }
    if (cpfStatus === "checking") {
      setMessage("Aguarde a validação do CPF para continuar.");
      return;
    }
    if (!facePhoto) {
      setMessage("Tire a foto pela câmera para continuar.");
      return;
    }
    register.mutate();
  }

  function handleCpfChange(value: string) {
    const cpf = formatCpfInput(value);
    const digits = onlyCpfDigits(cpf);
    setForm({ ...form, cpf });
    setCpfMessage("");
    if (digits.length < 11) {
      setCpfStatus("idle");
      return;
    }
    if (!isValidCpf(digits)) {
      setCpfStatus("invalid");
      setCpfMessage("CPF inválido. Verifique os números informados.");
      return;
    }
    setCpfStatus("idle");
  }

  const cpfInputError = cpfStatus === "duplicate" || cpfStatus === "invalid" ? cpfMessage : "";

  return (
    <main className="flex min-h-screen flex-col bg-[#0B1020] px-4 py-5 sm:px-6">
      <section className="mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center py-6">
        <div className="mb-7 text-center">
          <div className="mx-auto mb-5 flex h-10 w-10 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.055] text-slate-100">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <h1 className="text-3xl font-semibold text-slate-50 sm:text-5xl">Cadastro de colaborador</h1>
          <p className="mt-3 text-sm text-slate-400 sm:text-base">Preencha seus dados para ativar seu acesso.</p>
          <div className="mt-4 flex items-start gap-3 rounded-2xl border border-amber-500/50 bg-amber-900/40 px-4 py-3 text-left text-sm leading-6 text-amber-300">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
            <p>
              <strong>Atenção:</strong> não compartilhe o link de colaborador com visitantes. Ao final será feita uma auditoria de todos os usuários cadastrados.
            </p>
          </div>
        </div>

        <div className="w-full rounded-[28px] border border-white/10 bg-white/[0.04] p-4 shadow-[0_18px_70px_rgba(0,0,0,0.22)] backdrop-blur-xl sm:p-6">
          {register.isSuccess ? (
            <div className="flex flex-col items-center justify-center px-3 py-10 text-center">
              <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full border border-emerald-300/20 bg-emerald-400/12 text-emerald-200">
                <CheckCircle2 className="h-6 w-6" />
              </div>
              <h2 className="text-lg font-semibold text-slate-50">Cadastro realizado</h2>
              <p className="mt-2 max-w-sm text-sm leading-6 text-slate-400">{message}</p>
              <Button
                type="button"
                variant="secondary"
                className="mt-6"
                onClick={() => {
                  register.reset();
                  setFacePhoto(null);
                  setMessage("");
                  setForm({ fullName: "", cpf: "", phone: "", email: "" });
                  setCpfStatus("idle");
                  setCpfMessage("");
                }}
              >
                Fazer outro cadastro
              </Button>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4">
              <Input
                label="Nome completo"
                value={form.fullName}
                onChange={(event) => setForm({ ...form, fullName: event.target.value })}
                required
              />
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <Input
                    label="CPF"
                    value={form.cpf}
                    onChange={(event) => handleCpfChange(event.target.value)}
                    error={cpfInputError}
                    required
                    placeholder="000.000.000-00"
                    inputMode="numeric"
                    autoComplete="off"
                  />
                  {cpfStatus === "checking" ? <p className="mt-1 text-xs font-medium text-slate-400">Validando CPF...</p> : null}
                  {cpfStatus === "available" ? <p className="mt-1 text-xs font-medium text-emerald-200">{cpfMessage}</p> : null}
                  {cpfStatus === "error" ? <p className="mt-1 text-xs font-medium text-amber-200">{cpfMessage}</p> : null}
                </div>
                <Input
                  label="Telefone"
                  value={form.phone}
                  onChange={(event) => setForm({ ...form, phone: formatPhoneInput(event.target.value) })}
                  required
                  placeholder="(81) 99999-0000"
                  inputMode="tel"
                />
              </div>
              <Input
                label="E-mail"
                type="email"
                value={form.email}
                onChange={(event) => setForm({ ...form, email: event.target.value })}
                required
                placeholder="nome@empresa.com"
              />
              <div>
                <span className="mb-2 block text-sm font-medium text-slate-300">Foto facial</span>
                <CameraCapture value={facePhoto} onChange={setFacePhoto} disabled={register.isPending} />
              </div>
              {register.isError || message ? <ErrorState label={message} /> : null}
              <Button
                type="submit"
                icon={register.isPending ? undefined : ArrowRight}
                loading={register.isPending}
                disabled={!facePhoto}
                className="mt-1 h-11"
              >
                {register.isPending ? "Finalizando..." : "Finalizar cadastro"}
              </Button>
            </form>
          )}
        </div>
      </section>

      <footer className="pb-2 text-center text-xs text-slate-700">
        <span className="inline-flex items-center gap-1">
          <UserPlus className="h-3 w-3" />
          Cadastro seguro
        </span>
      </footer>
    </main>
  );
}

function formatPhoneInput(value: string) {
  const digits = value.replace(/\D/g, "").slice(0, 11);
  if (digits.length <= 2) return digits;
  if (digits.length <= 6) return `(${digits.slice(0, 2)}) ${digits.slice(2)}`;
  if (digits.length <= 10) return `(${digits.slice(0, 2)}) ${digits.slice(2, 6)}-${digits.slice(6)}`;
  return `(${digits.slice(0, 2)}) ${digits.slice(2, 7)}-${digits.slice(7)}`;
}

function publicEmployeeRegistrationErrorMessage(error: unknown) {
  const rawMessage = apiErrorMessage(error, "Não foi possível concluir o cadastro. Revise os dados e tente novamente.");
  if (rawMessage.toLowerCase().includes("cpf já cadastrado")) {
    return "CPF já cadastrado.";
  }
  return rawMessage;
}
