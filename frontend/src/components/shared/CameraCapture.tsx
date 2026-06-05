"use client";

/* eslint-disable @next/next/no-img-element */

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Camera, CheckCircle2, Loader2, RotateCcw, Video, XCircle } from "lucide-react";
import { Button } from "@/src/components/ui/Button";
import { faceService, type FaceValidationChecks } from "@/services/faceService";

type CameraCaptureProps = {
  value: File | null;
  onChange: (file: File | null) => void;
  disabled?: boolean;
};

type QualityStatus = "idle" | "validating" | "approved" | "rejected" | "error";
type QualityState = {
  status: QualityStatus;
  message: string;
  checks?: FaceValidationChecks;
};

const VALIDATING_MESSAGE = "Validando a foto no servidor...";
const BACKEND_ERROR_MESSAGE = "Não foi possível validar a foto agora. Tente novamente.";
const MULTIPLE_PEOPLE_MESSAGE = "Detectamos mais de uma pessoa na imagem. Tire uma foto individual, com apenas o rosto da pessoa cadastrada.";
const EYES_NOT_VISIBLE_MESSAGE = "Mantenha os olhos abertos e olhando para a câmera.";

// Rótulos das checagens reais retornadas pelo backend (sem decisão no client).
const CHECK_LABELS: { key: keyof FaceValidationChecks; label: string }[] = [
  { key: "faceDetected", label: "Rosto detectado" },
  { key: "singleFace", label: "Apenas uma pessoa" },
  { key: "brightnessOk", label: "Iluminação" },
  { key: "sharpnessOk", label: "Nitidez" },
  { key: "contrastOk", label: "Contraste" },
  { key: "centeredOk", label: "Centralização" },
  { key: "faceSizeOk", label: "Tamanho do rosto" },
  { key: "faceFullyVisibleOk", label: "Rosto visível" },
  { key: "eyesVisibleOk", label: "Olhos visíveis" },
  { key: "qualityAfterCompressionOk", label: "Qualidade após compressão" },
  { key: "finalCompressedSizeOk", label: "Arquivo final" }
];

function hasBooleanCheck(checks: FaceValidationChecks | undefined, key: keyof FaceValidationChecks): boolean {
  return !!checks && Object.prototype.hasOwnProperty.call(checks, key) && typeof checks[key] === "boolean";
}

function isCameraAvailable(): boolean {
  if (typeof window === "undefined") return false;
  const isSecure = window.location.protocol === "https:" || window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";
  return isSecure && typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
}

export function CameraCapture({ value, onChange, disabled }: CameraCaptureProps) {
  const cameraSupported = useMemo(() => isCameraAvailable(), []);
  const [cameraActive, setCameraActive] = useState(false);
  const [candidateFile, setCandidateFile] = useState<File | null>(null);
  const [quality, setQuality] = useState<QualityState>({ status: "idle", message: "" });
  const [error, setError] = useState("");
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
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

  async function validateAndAcceptPhoto(file: File) {
    const runId = validationRunRef.current + 1;
    validationRunRef.current = runId;
    setCandidateFile(file);
    setQuality({ status: "validating", message: VALIDATING_MESSAGE });
    onChange(null);

    try {
      const result = await faceService.validate(file);
      if (validationRunRef.current !== runId) return;

      const multiplePeopleDetected = result.checks.secondaryFaceDetected || !result.checks.singleFace;
      const eyesVisible = result.checks.eyesVisibleOk === true;
      if (result.approved && !multiplePeopleDetected && eyesVisible) {
        setQuality({ status: "approved", message: result.message, checks: result.checks });
        setCandidateFile(null);
        onChange(file);
        return;
      }
      // Reprovado pelo backend — mostra o motivo real e NÃO aceita a foto.
      setQuality({
        status: "rejected",
        message: multiplePeopleDetected
          ? MULTIPLE_PEOPLE_MESSAGE
          : result.approved && !eyesVisible
            ? EYES_NOT_VISIBLE_MESSAGE
            : result.message,
        checks: result.checks
      });
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
      {!cameraSupported && (
        <div className="mb-3 flex items-start gap-2 rounded-xl border border-amber-300/20 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-100">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-300" />
          <span>
            A câmera ao vivo não está disponível neste navegador.
            <br />
            Use HTTPS ou localhost e permita o acesso à câmera para continuar.
          </span>
        </div>
      )}

      {/* ─── ABA CÂMERA AO VIVO ─── */}
      {cameraSupported && (
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
    </div>
  );
}

function CameraGuide() {
  return (
    <>
      <div className="pointer-events-none absolute inset-x-3 top-3 rounded-xl border border-white/10 bg-black/45 px-3 py-2 text-center text-xs font-bold text-red-400 shadow-lg">
        Tire uma foto individual, de frente, com boa iluminação, fundo limpo, olhos abertos e rosto totalmente visível.
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
  const visibleChecks = quality.checks
    ? CHECK_LABELS.filter(({ key }) => hasBooleanCheck(quality.checks, key))
    : [];

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
      {visibleChecks.length > 0 && !validating ? (
        <div className="mt-3 grid gap-2 text-xs font-medium text-slate-300 sm:grid-cols-2">
          {visibleChecks.map(({ key, label }) => (
            <CheckPill key={key} label={label} ok={quality.checks?.[key] === true} />
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
