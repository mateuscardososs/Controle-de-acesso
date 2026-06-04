"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Camera, CheckCircle2 } from "lucide-react";
import { useParams } from "next/navigation";
import { apiErrorMessage } from "@/lib/errors";
import { guestService } from "@/services/guestService";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input } from "@/src/components/ui/Input";
import { ErrorState, LoadingState } from "@/src/components/shared/AsyncState";
import { CameraCapture } from "@/src/components/shared/CameraCapture";

export default function GuestRegistrationPage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const registration = useQuery({ queryKey: ["guest-registration", token], queryFn: () => guestService.publicRegistration(token), retry: false });
  const [phone, setPhone] = useState("");
  const [company, setCompany] = useState("");
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [message, setMessage] = useState("");

  const complete = useMutation({
    mutationFn: () => {
      if (!facePhoto) throw new Error("Tire a foto pela câmera para continuar.");
      return guestService.completeRegistration(token, { phone, company, facePhoto });
    },
    onSuccess: () => setMessage("Cadastro concluido com sucesso. Obrigado!"),
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível concluir o cadastro."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!facePhoto) {
      setMessage("Tire a foto pela câmera para continuar.");
      return;
    }
    complete.mutate();
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-10">
      <Card className="w-full max-w-2xl overflow-hidden">
        <div className="border-b border-white/10 bg-[#070B15]/80 px-6 py-7 text-white">
          <p className="text-sm font-bold uppercase tracking-[0.18em] text-red-200">Cadastro de visitante</p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight">Controle de Acesso</h1>
          <p className="mt-2 text-sm text-slate-300">Plataforma multiempresa</p>
        </div>
        <CardContent>
          {registration.isLoading ? <LoadingState label="Validando convite..." /> : null}
          {registration.isError ? <ErrorState label="Convite inválido, expirado ou já utilizado." /> : null}
          {registration.data && complete.isSuccess ? (
            <div className="flex flex-col items-center justify-center py-10 text-center">
              <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full border border-emerald-300/20 bg-emerald-400/12 text-emerald-200">
                <CheckCircle2 className="h-7 w-7" />
              </div>
              <h2 className="text-xl font-semibold text-slate-50">Cadastro concluido</h2>
              <p className="mt-2 text-sm text-slate-500">{message}</p>
            </div>
          ) : null}
          {registration.data && !complete.isSuccess ? (
            <form onSubmit={submit} className="grid gap-5">
              <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-4">
                <h2 className="text-lg font-semibold text-slate-50">{registration.data.fullName}</h2>
                <p className="mt-1 text-sm text-slate-400">{registration.data.visitReason} · Responsavel: {registration.data.hostName}</p>
                <p className="mt-2 text-xs font-medium text-slate-500">
                  {new Date(registration.data.visitStart).toLocaleString("pt-BR")} ate {new Date(registration.data.visitEnd).toLocaleString("pt-BR")}
                </p>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <Input label="Telefone" value={phone} onChange={(event) => setPhone(event.target.value)} />
                <Input label="Empresa" value={company} onChange={(event) => setCompany(event.target.value)} />
              </div>
              <div>
                <span className="mb-2 block text-sm font-semibold text-slate-300">Foto facial</span>
                <CameraCapture value={facePhoto} onChange={setFacePhoto} disabled={complete.isPending} />
              </div>
              {message ? <ErrorState label={message} /> : null}
              <Button type="submit" icon={Camera} loading={complete.isPending} disabled={!facePhoto}>Concluir cadastro</Button>
            </form>
          ) : null}
        </CardContent>
      </Card>
    </main>
  );
}
