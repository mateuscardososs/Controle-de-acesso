"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { PlayCircle } from "lucide-react";
import { apiErrorMessage } from "@/lib/errors";
import { deviceService } from "@/services/deviceService";
import { simulatorService } from "@/services/simulatorService";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { Input, Select } from "@/src/components/ui/Input";
import { ErrorState } from "./AsyncState";

export function AccessEventSimulator({ onSuccess }: { onSuccess?: () => void }) {
  const devices = useQuery({ queryKey: ["devices"], queryFn: deviceService.list });
  const [cpf, setCpf] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [eventType, setEventType] = useState<"ENTRY" | "EXIT" | "ACCESS_DENIED" | "COMMUNICATION_FAILURE">("ENTRY");
  const [result, setResult] = useState<"ALLOWED" | "DENIED" | "ERROR">("ALLOWED");
  const [message, setMessage] = useState("");

  const simulate = useMutation({
    mutationFn: () => simulatorService.accessEvent({ cpf, deviceId, eventType, result }),
    onSuccess: () => {
      setMessage("Evento simulado com sucesso. O feed sera atualizado pelo realtime ou pela proxima consulta.");
      onSuccess?.();
    },
    onError: (error) => setMessage(apiErrorMessage(error, "Não foi possível simular o evento."))
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    simulate.mutate();
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <PlayCircle className="h-5 w-5 text-brand-wine" />
          <h2 className="text-base font-semibold text-slate-50">Simular evento</h2>
        </div>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-4">
          <Input label="CPF" value={cpf} onChange={(event) => setCpf(event.target.value)} required placeholder="00000000000" />
          <Select label="Dispositivo" value={deviceId} onChange={(event) => setDeviceId(event.target.value)} required>
            <option value="">Selecione</option>
            {devices.data?.map((device) => (
              <option key={device.id} value={device.id}>
                {device.name} · {device.ipAddress}
              </option>
            ))}
          </Select>
          <div className="grid gap-4 sm:grid-cols-2">
            <Select label="Tipo" value={eventType} onChange={(event) => setEventType(event.target.value as typeof eventType)}>
              <option value="ENTRY">Entrada</option>
              <option value="EXIT">Saida</option>
              <option value="ACCESS_DENIED">Acesso negado</option>
              <option value="COMMUNICATION_FAILURE">Falha comunicacao</option>
            </Select>
            <Select label="Resultado" value={result} onChange={(event) => setResult(event.target.value as typeof result)}>
              <option value="ALLOWED">Permitido</option>
              <option value="DENIED">Negado</option>
              <option value="ERROR">Erro</option>
            </Select>
          </div>
          {simulate.isError ? <ErrorState label={message} /> : null}
          {simulate.isSuccess ? <div className="rounded-xl border border-emerald-300/20 bg-emerald-400/12 px-3 py-2 text-sm font-medium text-emerald-100">{message}</div> : null}
          <Button type="submit" loading={simulate.isPending} disabled={devices.isLoading}>
            Simular evento
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
