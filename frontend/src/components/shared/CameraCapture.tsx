"use client";

/* eslint-disable @next/next/no-img-element */

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Camera, CheckCircle2, Image, Loader2, RotateCcw, Upload, Video, X, XCircle } from "lucide-react";
import { Button } from "@/src/components/ui/Button";
import { faceService, type FaceValidationChecks } from "@/services/faceService";

type CameraCaptureProps = {
  value: File | null;
  onChange: (file: File | null) => void;
  disabled?: boolean;
};

type ActiveTab = "camera" | "upload";
type QualityStatus = "idle" | "validating" | "approved" | "rejected" | "error";
type QualityState = {
  status: QualityStatus;
  message: string;
  checks?: FaceValidationChecks;
};

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const ALLOWED_EXTENSIONS = ["jpg", "jpeg", "png", "webp"];
const MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB — alinhado com FaceStorageService
const VALIDATING_MESSAGE = "Validando a foto no servidor...";
const BACKEND_ERROR_MESSAGE = "Não foi possível validar a foto agora. Tente novamente.";

// Rótulos das checagens reais retornadas pelo backend (sem decisão no client).
const CHECK_LABELS: { key: keyof FaceValidationChecks; label: string }[] = [
  { key: "faceDetected", label: "Rosto detectado" },
  { key: "singleFace", label: "Apenas um rosto" },
  { key: "brightnessOk", label: "Iluminação" },
  { key: "sharpnessOk", label: "Nitidez" },
  { key: "contrastOk", label: "Contraste" },
  { key: "centeredOk", label: "Centralização" },
  { key: "sizeOk", label: "Tamanho do rosto" },
  { key: "faceFullyVisibleOk", label: "Rosto descoberto" }
];

function isCameraAvailable(): boolean {
  if (typeof window === "undefined") return false;
  const isSecure = window.location.protocol === "https:" || window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";
  return isSecure && typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
}

export function CameraCapture({ value, onChange, disabled }: CameraCaptureProps) {
  const cameraSupported = useMemo(() => isCameraAvailable(), []);
  const [activeTab, setActiveTab] = useState<ActiveTab>(cameraSupported ? "camera" : "upload");
  const [cameraActive, setCameraActive] = useState(false);
  const [candidateFile, setCandidateFile] = useState<File | null>(null);
  const [quality, setQuality] = useState<QualityState>({ status: "idle", message: "" });
  const [error, setError] = useState("");
  const [uploadError, setUploadError] = useState("");
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const validationRunRef = useRef(0);

  const previewFile = candidateFile ?? value;
  const preview = useMemo(() => (previewFile ? URL.createObjectURL(previewFile) : ""), [previewFile]);

  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview);
    };
  }, [preview]);

  useEffect(() => {
    return () => stopCamera();
  }, []);

  function handleTabChange(tab: ActiveTab) {
    if (tab === "upload") stopCamera();
    setActiveTab(tab);
    setError("");
    setUploadError("");
  }

  // ─── Câmera ao vivo ───────────────────────────────────────────────

  async function startCamera() {
    setError("");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "user", width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setCameraActive(true);
    } catch (cameraError) {
      setError(cameraAccessErrorMessage(cameraError));
      setCameraActive(false);
    }
  }

  function stopCamera() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraActive(false);
  }

  function capture() {
    const video = videoRef.current;
    if (!video) return;
    const width = video.videoWidth || 960;
    const height = video.videoHeight || 720;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      setError("Não foi possível capturar a foto.");
      return;
    }
    ctx.drawImage(video, 0, 0, width, height);
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          setError("Não foi possível capturar a foto.");
          return;
        }
        stopCamera();
        void validateAndAcceptPhoto(new File([blob], "foto-visitante.jpg", { type: "image/jpeg" }));
      },
      "image/jpeg",
      0.92
    );
  }

  function retakeFromCamera() {
    clearPhoto();
    void startCamera();
  }

  // ─── Upload / câmera nativa ───────────────────────────────────────

  function handleFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    setUploadError("");
    const file = event.target.files?.[0];
    if (!file) return;

    // Pré-checagem visual apenas (formato/tamanho). A DECISÃO de aprovação é do backend.
    const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
    if (!ALLOWED_TYPES.includes(file.type) && !ALLOWED_EXTENSIONS.includes(ext)) {
      setUploadError("Formato inválido. Use JPG, PNG ou WEBP.");
      event.target.value = "";
      return;
    }
    if (file.size > MAX_SIZE_BYTES) {
      setUploadError("Imagem muito grande. O tamanho máximo é 5 MB.");
      event.target.value = "";
      return;
    }

    void validateAndAcceptPhoto(file);
    event.target.value = "";
  }

  function removeUpload() {
    clearPhoto();
    setUploadError("");
  }

  async function validateAndAcceptPhoto(file: File) {
    const runId = validationRunRef.current + 1;
    validationRunRef.current = runId;
    setCandidateFile(file);
    setQuality({ status: "validating", message: VALIDATING_MESSAGE });
    onChange(null);

    try {
      const result = await faceService.validate(file);
      if (validationRunRef.current !== runId) return;

      if (result.approved) {
        setQuality({ status: "approved", message: result.message, checks: result.checks });
        setCandidateFile(null);
        onChange(file);
        return;
      }
      // Reprovado pelo backend — mostra o motivo real e NÃO aceita a foto.
      setQuality({ status: "rejected", message: result.message, checks: result.checks });
      onChange(null);
    } catch (validationError) {
      if (validationRunRef.current !== runId) return;
      // Erro de backend/rede: NUNCA aprovar automaticamente.
      console.warn("FACE_PHOTO_BACKEND_VALIDATION_FAILED", validationError);
      setQuality({ status: "error", message: BACKEND_ERROR_MESSAGE });
      onChange(null);
    }
  }

  function clearPhoto() {
    validationRunRef.current += 1;
    setCandidateFile(null);
    setQuality({ status: "idle", message: "" });
    onChange(null);
  }

  const rejectedOrError = quality.status === "rejected" || quality.status === "error";

  // ─── Render ───────────────────────────────────────────────────────

  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
      {cameraSupported && (
        <div className="mb-3 flex gap-1 rounded-xl bg-white/5 p-1">
          <TabButton active={activeTab === "camera"} onClick={() => handleTabChange("camera")} icon={Video} label="Câmera ao vivo" />
          <TabButton active={activeTab === "upload"} onClick={() => handleTabChange("upload")} icon={Image} label="Foto / Galeria" />
        </div>
      )}

      {!cameraSupported && (
        <div className="mb-3 flex items-start gap-2 rounded-xl border border-amber-300/20 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-100">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-300" />
          <span>
            A câmera ao vivo não está disponível neste navegador.
            <br />
            Você ainda pode <strong>tirar uma foto</strong> ou <strong>selecionar uma imagem da galeria</strong> abaixo.
          </span>
        </div>
      )}

      {/* ─── ABA CÂMERA AO VIVO ─── */}
      {activeTab === "camera" && (
        <>
          <div className="relative overflow-hidden rounded-xl border border-white/10 bg-slate-950">
            {preview ? (
              <img src={preview} alt="Foto capturada" className="h-64 w-full object-cover" />
            ) : (
              <>
                <video ref={videoRef} playsInline muted className="h-64 w-full object-cover" />
                <CameraGuide />
              </>
            )}
          </div>

          {preview ? <QualityFeedback quality={quality} /> : null}

          {error && (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-red-300/20 bg-red-500/12 px-3 py-2 text-sm font-medium text-red-100">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <div className="mt-3 flex flex-wrap gap-2">
            {!preview && !cameraActive && (
              <Button type="button" variant="secondary" icon={Video} disabled={disabled} onClick={startCamera}>
                Ativar câmera
              </Button>
            )}
            {!preview && cameraActive && (
              <Button type="button" icon={Camera} disabled={disabled} onClick={capture}>
                Tirar foto
              </Button>
            )}
            {preview && (
              <Button
                type="button"
                variant={rejectedOrError ? "danger" : "secondary"}
                icon={RotateCcw}
                className={rejectedOrError ? "ring-1 ring-red-300/30" : undefined}
                disabled={disabled || quality.status === "validating"}
                onClick={retakeFromCamera}
              >
                {rejectedOrError ? "Tirar nova foto" : "Refazer"}
              </Button>
            )}
          </div>
        </>
      )}

      {/* ─── ABA UPLOAD / CÂMERA NATIVA ─── */}
      {(activeTab === "upload" || !cameraSupported) && (
        <>
          {preview ? (
            <div className="relative overflow-hidden rounded-xl border border-white/10 bg-slate-950">
              <img src={preview} alt="Foto selecionada" className="h-64 w-full object-cover" />
              {!disabled && (
                <button
                  type="button"
                  onClick={removeUpload}
                  className="absolute right-2 top-2 flex h-8 w-8 items-center justify-center rounded-full bg-black/60 text-white transition hover:bg-black/80"
                  aria-label="Remover foto"
                >
                  <X className="h-4 w-4" />
                </button>
              )}
            </div>
          ) : (
            <button
              type="button"
              disabled={disabled}
              onClick={() => fileInputRef.current?.click()}
              className="flex h-64 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed border-white/15 bg-white/[0.025] text-slate-400 transition hover:border-white/30 hover:bg-white/5 hover:text-slate-200 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <div className="flex h-12 w-12 items-center justify-center rounded-full border border-white/10 bg-white/5">
                <Upload className="h-5 w-5" />
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-slate-200">Toque para tirar foto ou escolher da galeria</p>
                <p className="mt-1 text-xs text-slate-500">JPG, PNG ou WEBP · máx. 5 MB</p>
              </div>
            </button>
          )}

          {preview ? <QualityFeedback quality={quality} /> : null}

          {uploadError && (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-red-300/20 bg-red-500/12 px-3 py-2 text-sm font-medium text-red-100">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{uploadError}</span>
            </div>
          )}

          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp"
            capture="user"
            className="sr-only"
            onChange={handleFileChange}
            disabled={disabled}
          />

          <div className="mt-3 flex flex-wrap gap-2">
            {preview ? (
              <Button
                type="button"
                variant={rejectedOrError ? "danger" : "secondary"}
                icon={RotateCcw}
                className={rejectedOrError ? "ring-1 ring-red-300/30" : undefined}
                disabled={disabled || quality.status === "validating"}
                onClick={() => fileInputRef.current?.click()}
              >
                {rejectedOrError ? "Tirar nova foto" : "Trocar foto"}
              </Button>
            ) : (
              <Button type="button" variant="secondary" icon={Upload} disabled={disabled} onClick={() => fileInputRef.current?.click()}>
                Selecionar imagem
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function CameraGuide() {
  return (
    <>
      <div className="pointer-events-none absolute inset-x-3 top-3 rounded-xl border border-white/10 bg-black/45 px-3 py-2 text-center text-xs font-bold text-red-400 shadow-lg">
        Tire a foto com fundo neutro e boa iluminação, sem objetos e pessoas ao redor
      </div>
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <div className="h-44 w-36 rounded-[50%] border-2 border-white/70 shadow-[0_0_0_999px_rgba(2,6,23,0.22)]" />
      </div>
    </>
  );
}

function QualityFeedback({ quality }: { quality: QualityState }) {
  if (quality.status === "idle") {
    return null;
  }

  const approved = quality.status === "approved";
  const validating = quality.status === "validating";

  return (
    <div className={`mt-3 rounded-xl border p-3 ${
      approved
        ? "border-emerald-300/20 bg-emerald-400/10 text-emerald-100"
        : validating
          ? "border-white/10 bg-white/[0.055] text-slate-200"
          : "border-red-300/20 bg-red-500/12 text-red-100"
    }`}>
      <div className="flex items-start gap-2 text-sm font-semibold">
        {validating ? (
          <Loader2 className="mt-0.5 h-4 w-4 animate-spin text-slate-300" />
        ) : approved ? (
          <CheckCircle2 className="mt-0.5 h-4 w-4 text-emerald-300" />
        ) : (
          <XCircle className="mt-0.5 h-4 w-4 text-red-300" />
        )}
        <span>{quality.message}</span>
      </div>
      {quality.checks && !validating ? (
        <div className="mt-3 grid gap-2 text-xs font-medium text-slate-300 sm:grid-cols-2">
          {CHECK_LABELS.map(({ key, label }) => (
            <CheckPill key={key} label={label} ok={Boolean(quality.checks?.[key])} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function CheckPill({ label, ok }: { label: string; ok: boolean }) {
  return (
    <div className="flex items-start gap-1.5 rounded-lg border border-white/10 bg-black/15 px-2 py-1.5 leading-snug">
      {ok ? (
        <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-200" />
      ) : (
        <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-red-200" />
      )}
      <span>{label}: {ok ? "OK" : "ajustar"}</span>
    </div>
  );
}

// ─── Sub-componente tab ────────────────────────────────────────────────────────

function TabButton({
  active,
  onClick,
  icon: Icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ElementType;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex flex-1 items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-all ${
        active
          ? "bg-white/10 text-slate-50 shadow-sm"
          : "text-slate-400 hover:text-slate-200"
      }`}
    >
      <Icon className="h-4 w-4" />
      <span>{label}</span>
    </button>
  );
}

// ─── Helper ────────────────────────────────────────────────────────────────────

function cameraAccessErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "PermissionDeniedError") {
      return "Câmera não autorizada. Permita o acesso à câmera no navegador e tente novamente.";
    }
    if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError") {
      return "Câmera indisponível neste dispositivo.";
    }
    if (error.name === "NotReadableError" || error.name === "TrackStartError") {
      return "Não foi possível iniciar a câmera. Feche outros aplicativos que possam estar usando a câmera.";
    }
    if (error.name === "SecurityError") {
      return "Câmera bloqueada. Use HTTPS ou localhost e autorize o navegador.";
    }
  }
  return error instanceof Error ? error.message : "Não foi possível acessar a câmera.";
}
