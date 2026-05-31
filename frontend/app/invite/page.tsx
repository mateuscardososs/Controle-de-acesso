"use client";

import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowRight, CheckCircle2, Loader2, MapPin, ShieldCheck } from "lucide-react";
import { apiErrorMessage } from "@/lib/errors";
import { formatCpfInput, isValidCpf, onlyCpfDigits } from "@/lib/cpf";
import { checkinService } from "@/services/checkinService";
import { CameraCapture } from "@/src/components/shared/CameraCapture";
import { Input } from "@/src/components/ui/Input";
import { ErrorState } from "@/src/components/shared/AsyncState";

type Step = "cpf" | "photo" | "done";

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error("Não foi possível ler a foto."));
    reader.readAsDataURL(file);
  });
}

export default function InviteCheckinPage() {
  const [step, setStep] = useState<Step>("cpf");
  const [cpf, setCpf] = useState("");
  const [cpfError, setCpfError] = useState("");
  const [message, setMessage] = useState("");
  const [guestName, setGuestName] = useState("");
  const [lounge, setLounge] = useState<string | null>(null);
  const [facePhoto, setFacePhoto] = useState<File | null>(null);

  const validate = useMutation({
    mutationFn: () => checkinService.validateCpf(onlyCpfDigits(cpf)),
    onSuccess: (result) => {
      if (!result.found) {
        setMessage(result.message || "CPF não cadastrado. Entre em contato com o organizador.");
        return;
      }
      setGuestName(result.fullName ?? "");
      setLounge(result.invitedLounge ?? null);
      setMessage("");
      setStep("photo");
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível validar o CPF. Tente novamente."))
  });

  const complete = useMutation({
    mutationFn: async () => {
      if (!facePhoto) throw new Error("Tire a foto para continuar.");
      const base64 = await fileToBase64(facePhoto);
      return checkinService.completeRegistration(onlyCpfDigits(cpf), base64);
    },
    onSuccess: (result) => {
      setGuestName(result.fullName ?? guestName);
      setLounge(result.invitedLounge ?? lounge);
      setMessage("");
      setStep("done");
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível concluir o cadastro. Tente novamente."))
  });

  function submitCpf(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isValidCpf(cpf)) {
      setCpfError("CPF inválido. Verifique os números informados.");
      return;
    }
    setCpfError("");
    setMessage("");
    validate.mutate();
  }

  return (
    <main className="flex min-h-screen flex-col bg-[#0B1020] px-4 py-8 sm:px-6">
      <section className="mx-auto flex w-full max-w-md flex-1 flex-col items-center justify-center gap-8">

        {/* Header — visível em todos os passos */}
        <div className="flex flex-col items-center text-center">
          <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.055] text-slate-100">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <h1 className="text-3xl font-bold tracking-tight text-slate-50 sm:text-4xl">Check-in do evento</h1>
          <p className="mt-2 text-sm text-slate-400">Valide seu CPF para liberar o acesso.</p>
        </div>

        <div className="w-full rounded-[28px] border border-white/10 bg-white/[0.04] p-6 shadow-[0_18px_70px_rgba(0,0,0,0.22)] backdrop-blur-xl sm:p-7">

          {/* PASSO 1 — Validação de CPF */}
          {step === "cpf" && (
            <form onSubmit={submitCpf} className="grid gap-5">
              <Input
                label="CPF"
                value={cpf}
                onChange={(event) => {
                  setCpf(formatCpfInput(event.target.value));
                  setCpfError("");
                  setMessage("");
                }}
                error={cpfError}
                required
                placeholder="000.000.000-00"
                inputMode="numeric"
                autoFocus
              />
              {message ? <ErrorState label={message} /> : null}
              <button
                type="submit"
                disabled={validate.isPending}
                className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-full bg-white px-5 text-base font-semibold text-slate-950 transition hover:bg-slate-200 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {validate.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {validate.isPending ? "Validando..." : "Entrar"}
                {!validate.isPending ? <ArrowRight className="h-4 w-4" /> : null}
              </button>
            </form>
          )}

          {/* PASSO 2 — Boas-vindas + captura de foto */}
          {step === "photo" && (
            <div className="grid gap-5">
              <div className="text-center">
                <h2 className="text-2xl font-bold text-slate-50 sm:text-3xl">
                  Seja bem-vindo(a),<br />{guestName}!
                </h2>
                {lounge ? (
                  <span className="mt-3 inline-flex items-center gap-1.5 rounded-full border border-sky-300/20 bg-sky-400/12 px-3 py-1 text-sm font-semibold text-sky-200">
                    <MapPin className="h-3.5 w-3.5" />
                    Camarote: {lounge}
                  </span>
                ) : null}
              </div>

              <CameraCapture value={facePhoto} onChange={setFacePhoto} disabled={complete.isPending} />

              {message ? <ErrorState label={message} /> : null}

              <button
                type="button"
                disabled={!facePhoto || complete.isPending}
                onClick={() => { setMessage(""); complete.mutate(); }}
                className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-full bg-white px-5 text-base font-semibold text-slate-950 transition hover:bg-slate-200 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {complete.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {complete.isPending ? "Confirmando..." : "Confirmar cadastro"}
                {!complete.isPending ? <ArrowRight className="h-4 w-4" /> : null}
              </button>
            </div>
          )}

          {/* PASSO 3 — Confirmação final (sem botão de voltar) */}
          {step === "done" && (
            <div className="flex flex-col items-center justify-center px-3 py-10 text-center">
              <div className="mb-5 flex h-16 w-16 items-center justify-center rounded-full border border-emerald-300/20 bg-emerald-400/12 text-emerald-200">
                <CheckCircle2 className="h-9 w-9" />
              </div>
              <h2 className="text-2xl font-bold text-slate-50">{guestName}</h2>
              {lounge ? (
                <span className="mt-3 inline-flex items-center gap-1.5 rounded-full border border-sky-300/20 bg-sky-400/12 px-3 py-1 text-sm font-semibold text-sky-200">
                  <MapPin className="h-3.5 w-3.5" />
                  Camarote: {lounge}
                </span>
              ) : null}
              <p className="mt-6 max-w-xs text-base leading-7 text-slate-400">
                Seu acesso facial foi registrado.<br />Dirija-se à entrada.
              </p>
            </div>
          )}

        </div>
      </section>

      <footer className="pb-3 pt-8 text-center text-xs text-slate-700">Check-in seguro</footer>
    </main>
  );
}
