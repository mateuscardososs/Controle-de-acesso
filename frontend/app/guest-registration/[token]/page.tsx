"use client";

import { ChangeEvent, FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Camera, CheckCircle2, UploadCloud } from "lucide-react";
import { useParams } from "next/navigation";
import { apiErrorMessage } from "@/lib/errors";
import { guestService } from "@/services/guestService";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent } from "@/src/components/ui/Card";
import { Input } from "@/src/components/ui/Input";
import { EmptyState, ErrorState, LoadingState } from "@/src/components/shared/AsyncState";

export default function GuestRegistrationPage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const registration = useQuery({ queryKey: ["guest-registration", token], queryFn: () => guestService.publicRegistration(token), retry: false });
  const [phone, setPhone] = useState("");
  const [company, setCompany] = useState("");
  const [facePhoto, setFacePhoto] = useState<File | null>(null);
  const [message, setMessage] = useState("");

  const preview = useMemo(() => (facePhoto ? URL.createObjectURL(facePhoto) : ""), [facePhoto]);

  const complete = useMutation({
    mutationFn: () => {
      if (!facePhoto) throw new Error("Envie uma foto facial.");
      return guestService.completeRegistration(token, { phone, company, facePhoto });
    },
    onSuccess: () => setMessage("Cadastro concluido com sucesso. Obrigado!"),
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível concluir o cadastro."))
  });

  function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) setFacePhoto(file);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    complete.mutate();
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-100 px-4 py-10">
      <Card className="w-full max-w-2xl overflow-hidden">
        <div className="bg-slate-950 px-6 py-7 text-white">
          <p className="text-sm font-bold uppercase tracking-wide text-red-200">Cadastro de visitante</p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight">Controle de Acesso</h1>
          <p className="mt-2 text-sm text-slate-300">Sport Club do Recife</p>
        </div>
        <CardContent>
          {registration.isLoading ? <LoadingState label="Validando convite..." /> : null}
          {registration.isError ? <ErrorState label="Convite inválido, expirado ou já utilizado." /> : null}
          {registration.data && complete.isSuccess ? (
            <div className="flex flex-col items-center justify-center py-10 text-center">
              <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-700">
                <CheckCircle2 className="h-7 w-7" />
              </div>
              <h2 className="text-xl font-semibold text-slate-950">Cadastro concluido</h2>
              <p className="mt-2 text-sm text-slate-500">{message}</p>
            </div>
          ) : null}
          {registration.data && !complete.isSuccess ? (
            <form onSubmit={submit} className="grid gap-5">
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <h2 className="text-lg font-semibold text-slate-950">{registration.data.fullName}</h2>
                <p className="mt-1 text-sm text-slate-600">{registration.data.visitReason} · Responsavel: {registration.data.hostName}</p>
                <p className="mt-2 text-xs font-medium text-slate-500">
                  {new Date(registration.data.visitStart).toLocaleString("pt-BR")} ate {new Date(registration.data.visitEnd).toLocaleString("pt-BR")}
                </p>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <Input label="Telefone" value={phone} onChange={(event) => setPhone(event.target.value)} />
                <Input label="Empresa" value={company} onChange={(event) => setCompany(event.target.value)} />
              </div>
              <label className="block">
                <span className="mb-2 block text-sm font-semibold text-slate-700">Foto facial</span>
                <div className="flex min-h-52 cursor-pointer flex-col items-center justify-center rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center transition hover:border-sport-red hover:bg-red-50/30">
                  <input className="sr-only" type="file" accept="image/png,image/jpeg,image/webp" onChange={onFile} />
                  {preview ? (
                    <img src={preview} alt="Preview da foto facial" className="h-44 w-44 rounded-2xl object-cover shadow-sm" />
                  ) : (
                    <>
                      <UploadCloud className="h-10 w-10 text-slate-400" />
                      <p className="mt-3 text-sm font-semibold text-slate-800">Clique para enviar uma imagem</p>
                      <p className="mt-1 text-xs text-slate-500">PNG, JPG ou WEBP ate 5MB</p>
                    </>
                  )}
                </div>
              </label>
              {message && complete.isError ? <ErrorState label={message} /> : null}
              {!registration.data.requiresFacePhoto ? <EmptyState label="Foto já enviada." /> : null}
              <Button type="submit" icon={Camera} loading={complete.isPending}>Concluir cadastro</Button>
            </form>
          ) : null}
        </CardContent>
      </Card>
    </main>
  );
}
