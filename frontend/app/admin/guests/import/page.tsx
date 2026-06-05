"use client";

import { ChangeEvent, DragEvent, useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, ArrowLeft, CheckCircle2, Download, FileSpreadsheet, Loader2, UploadCloud, XCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { AdminShell } from "@/components/AdminShell";
import { PageHeader } from "@/components/PageHeader";
import { displayAreaName } from "@/lib/areaLabels";
import { apiErrorMessage } from "@/lib/errors";
import { formatCpfDisplay } from "@/lib/cpf";
import { guestService, GuestImportPreviewResponse, GuestImportReport } from "@/services/guestService";
import { Button } from "@/src/components/ui/Button";
import { Card, CardContent, CardHeader } from "@/src/components/ui/Card";
import { EmptyState } from "@/src/components/shared/AsyncState";

const ACCEPTED_EXTENSIONS = [".xlsx", ".xls", ".csv", ".pdf"];

export default function GuestImportPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<GuestImportPreviewResponse | null>(null);
  const [report, setReport] = useState<GuestImportReport | null>(null);
  const [error, setError] = useState("");
  const [dragActive, setDragActive] = useState(false);

  const canImport = Boolean(file && preview && preview.missingRequiredColumns.length === 0);
  const fileMeta = useMemo(() => file ? `${file.name} · ${formatFileSize(file.size)}` : "Nenhum arquivo selecionado", [file]);

  const previewImport = useMutation({
    mutationFn: (selectedFile: File) => guestService.previewImport(selectedFile),
    onSuccess: (result) => {
      setPreview(result);
      setReport(null);
      setError("");
    },
    onError: (unknownError) => {
      setPreview(null);
      setError(apiErrorMessage(unknownError, "Não foi possível ler o arquivo."));
    }
  });

  const importGuests = useMutation({
    mutationFn: () => {
      if (!file) throw new Error("Selecione um arquivo.");
      return guestService.importGuests(file);
    },
    onSuccess: (result) => {
      setReport(result);
      setError("");
      queryClient.invalidateQueries({ queryKey: ["guests"] });
    },
    onError: (unknownError) => setError(apiErrorMessage(unknownError, "Não foi possível importar convidados."))
  });

  const template = useMutation({
    mutationFn: guestService.downloadImportTemplate,
    onSuccess: (blob) => downloadBlob(blob, "modelo-importacao-convidados.xlsx"),
    onError: (unknownError) => setError(apiErrorMessage(unknownError, "Não foi possível baixar o modelo."))
  });

  function selectFile(selectedFile?: File | null) {
    setError("");
    setReport(null);
    setPreview(null);
    if (!selectedFile) {
      setFile(null);
      return;
    }
    if (!isAcceptedFile(selectedFile)) {
      setFile(null);
      setError("Formato inválido. Use .xlsx, .xls, .csv ou .pdf.");
      return;
    }
    setFile(selectedFile);
    previewImport.mutate(selectedFile);
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault();
    setDragActive(false);
    selectFile(event.dataTransfer.files.item(0));
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    selectFile(event.target.files?.item(0));
    event.target.value = "";
  }

  return (
    <AdminShell>
      <PageHeader
        eyebrow="Visitantes"
        title="Importar convidados"
        description="Pré-cadastro em massa para check-in facial por CPF."
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" icon={ArrowLeft} onClick={() => router.push("/guests")}>Voltar</Button>
            <Button variant="secondary" icon={Download} loading={template.isPending} onClick={() => template.mutate()}>Baixar modelo</Button>
          </div>
        )}
      />

      {error ? (
        <div className="mb-4 flex items-start gap-3 rounded-xl border border-red-300/20 bg-red-500/14 px-4 py-3 text-sm font-medium text-red-100">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-slate-50">Arquivo</h2>
                <p className="mt-1 text-xs text-slate-500">{fileMeta}</p>
              </div>
              <FileSpreadsheet className="h-5 w-5 text-slate-400" />
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <label
              onDragEnter={(event) => { event.preventDefault(); setDragActive(true); }}
              onDragOver={(event) => event.preventDefault()}
              onDragLeave={() => setDragActive(false)}
              onDrop={handleDrop}
              className={`flex min-h-[210px] cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed px-5 py-8 text-center transition ${
                dragActive ? "border-brand-red bg-brand-red/10" : "border-white/15 bg-white/[0.04] hover:border-white/25 hover:bg-white/[0.07]"
              }`}
            >
              <UploadCloud className="mb-3 h-10 w-10 text-slate-300" />
              <span className="text-sm font-semibold text-slate-100">Solte o arquivo aqui ou selecione do computador</span>
              <span className="mt-2 text-xs text-slate-500">XLSX, XLS, CSV ou PDF · até 1000 linhas</span>
              <input
                type="file"
                accept={ACCEPTED_EXTENSIONS.join(",")}
                className="sr-only"
                onChange={handleFileChange}
              />
            </label>

            <div className="grid gap-2 text-xs text-slate-400 sm:grid-cols-2">
              <Requirement ok={Boolean(preview && !preview.missingRequiredColumns.includes("Nome Completo"))} label="Nome completo" />
              <Requirement ok={Boolean(preview && !preview.missingRequiredColumns.includes("CPF"))} label="CPF" />
              <Requirement ok={Boolean(preview && !preview.missingRequiredColumns.includes("Telefone"))} label="Telefone" />
              <Requirement ok={Boolean(preview && !preview.missingRequiredColumns.includes("Camarote"))} label="Camarote" />
            </div>

            <Button
              className="w-full"
              icon={CheckCircle2}
              loading={importGuests.isPending}
              disabled={!canImport || previewImport.isPending || importGuests.isPending}
              onClick={() => importGuests.mutate()}
            >
              Confirmar importação
            </Button>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-base font-semibold text-slate-50">Preview</h2>
              {previewImport.isPending ? <Loader2 className="h-4 w-4 animate-spin text-slate-400" /> : null}
            </div>
          </CardHeader>
          <CardContent>
            {!file && !previewImport.isPending ? <EmptyState label="Nenhum arquivo selecionado." /> : null}
            {preview?.missingRequiredColumns.length ? (
              <div className="mb-4 rounded-xl border border-amber-300/20 bg-amber-400/10 px-4 py-3 text-sm text-amber-100">
                Colunas ausentes: {preview.missingRequiredColumns.join(", ")}.
              </div>
            ) : null}
            {preview ? (
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Metric label="Linhas" value={preview.totalRowsInFile} />
                  <Metric label="Headers" value={preview.detectedHeaders.length} />
                  <Metric label="Pendências" value={preview.missingRequiredColumns.length} />
                </div>
                {preview.preview.length ? (
                  <div className="overflow-x-auto rounded-xl border border-white/10">
                    <table className="min-w-full divide-y divide-white/10 text-sm">
                      <thead className="bg-white/[0.04] text-left text-xs uppercase tracking-wide text-slate-500">
                        <tr>
                          <th className="px-3 py-2">Linha</th>
                          <th className="px-3 py-2">Nome</th>
                          <th className="px-3 py-2">CPF</th>
                          <th className="px-3 py-2">Telefone</th>
                          <th className="px-3 py-2">Camarote</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-white/10 text-slate-200">
                        {preview.preview.map((row) => (
                          <tr key={`${row.line}-${row.cpf}`}>
                            <td className="px-3 py-2 font-mono text-xs text-slate-500">{row.line}</td>
                            <td className="px-3 py-2 font-medium text-slate-100">{row.fullName || "—"}</td>
                            <td className="px-3 py-2 font-mono text-xs">{formatCpfDisplay(row.cpf)}</td>
                            <td className="px-3 py-2">{row.phone || "—"}</td>
                            <td className="px-3 py-2">{row.invitedLounge ? displayAreaName(row.invitedLounge) : "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : <EmptyState label="Arquivo sem linhas de dados." />}
              </div>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {report ? (
        <Card className="mt-5">
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-base font-semibold text-slate-50">Relatório</h2>
              <CheckCircle2 className="h-5 w-5 text-emerald-300" />
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <Metric label="Criados" value={report.created} tone="success" />
              <Metric label="Atualizados" value={report.updated} tone="info" />
              <Metric label="Ignorados" value={report.skipped} tone="muted" />
              <Metric label="Erros" value={report.errors.length} tone={report.errors.length ? "danger" : "muted"} />
            </div>
            <p className="text-sm font-semibold text-slate-200">
              {report.created} criados | {report.updated} atualizados | {report.skipped} ignorados
            </p>
            {report.errors.length ? (
              <div className="overflow-x-auto rounded-xl border border-red-300/20">
                <table className="min-w-full divide-y divide-red-300/15 text-sm">
                  <thead className="bg-red-500/10 text-left text-xs uppercase tracking-wide text-red-200">
                    <tr>
                      <th className="px-3 py-2">Linha</th>
                      <th className="px-3 py-2">CPF</th>
                      <th className="px-3 py-2">Motivo</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-red-300/10 text-red-100">
                    {report.errors.map((row) => (
                      <tr key={`${row.line}-${row.reason}`}>
                        <td className="px-3 py-2 font-mono text-xs">{row.line}</td>
                        <td className="px-3 py-2 font-mono text-xs">{row.cpf ? formatCpfDisplay(row.cpf) : "—"}</td>
                        <td className="px-3 py-2">{row.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="flex items-center gap-2 rounded-xl border border-emerald-300/20 bg-emerald-400/10 px-4 py-3 text-sm font-medium text-emerald-100">
                <CheckCircle2 className="h-4 w-4" />
                Importação concluída sem erros de linha.
              </div>
            )}
          </CardContent>
        </Card>
      ) : null}
    </AdminShell>
  );
}

function Requirement({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span className={`inline-flex items-center gap-2 rounded-lg border px-3 py-2 ${
      ok ? "border-emerald-300/20 bg-emerald-400/10 text-emerald-100" : "border-white/10 bg-white/[0.04] text-slate-400"
    }`}>
      {ok ? <CheckCircle2 className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />}
      {label}
    </span>
  );
}

function Metric({ label, value, tone = "muted" }: { label: string; value: number; tone?: "success" | "info" | "danger" | "muted" }) {
  const toneClass = {
    success: "text-emerald-200",
    info: "text-sky-200",
    danger: "text-red-200",
    muted: "text-slate-100"
  }[tone];
  return (
    <div className="rounded-xl border border-white/10 bg-white/[0.045] px-4 py-3">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${toneClass}`}>{value}</p>
    </div>
  );
}

function isAcceptedFile(file: File) {
  const lowerName = file.name.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((extension) => lowerName.endsWith(extension));
}

function formatFileSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
