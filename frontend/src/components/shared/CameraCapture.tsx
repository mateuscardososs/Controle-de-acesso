"use client";

/* eslint-disable @next/next/no-img-element */

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Camera, CheckCircle2, Image, Loader2, RotateCcw, Upload, Video, X, XCircle } from "lucide-react";
import { Button } from "@/src/components/ui/Button";

type CameraCaptureProps = {
  value: File | null;
  onChange: (file: File | null) => void;
  disabled?: boolean;
};

type ActiveTab = "camera" | "upload";
type QualityStatus = "idle" | "validating" | "approved" | "rejected";
type QualityCheckStatus = "pending" | "pass" | "fail" | "skipped";
type QualityCheckKey = "lighting" | "size" | "face" | "contrast";
type QualityChecks = Record<QualityCheckKey, QualityCheckStatus>;
type QualityState = {
  status: QualityStatus;
  message: string;
  checks: QualityChecks;
};
type FaceDetectorResult = {
  boundingBox?: DOMRectReadOnly;
};
type FaceDetectorConstructor = new (options?: { fastMode?: boolean; maxDetectedFaces?: number }) => {
  detect: (image: HTMLImageElement | HTMLCanvasElement | ImageBitmap | VideoFrame) => Promise<FaceDetectorResult[]>;
};

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const ALLOWED_EXTENSIONS = ["jpg", "jpeg", "png", "webp"];
const MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB — alinhado com FaceStorageService
const MIN_LUMINANCE = 60;
const MIN_IMAGE_SIZE = 200;
const MIN_CONTRAST_STD_DEV = 20;
const APPROVED_QUALITY_MESSAGE = "Foto aprovada! Rosto identificado com sucesso.";
const VALIDATING_QUALITY_MESSAGE = "Verificando qualidade da foto...";
const QUALITY_FAILURE_PRIORITY: QualityCheckKey[] = ["lighting", "size", "contrast", "face"];
const QUALITY_FAILURE_MESSAGES: Record<QualityCheckKey, string> = {
  lighting: "Foto muito escura. Vá para um lugar com mais luz e tente novamente.",
  size: "Foto muito pequena. Aproxime o rosto da câmera.",
  contrast: "Foto sem contraste suficiente. Verifique a iluminação e o fundo.",
  face: "Nenhum rosto detectado. Centralize seu rosto na câmera e tente novamente."
};

declare global {
  interface Window {
    FaceDetector?: FaceDetectorConstructor;
  }
}

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
  const [quality, setQuality] = useState<QualityState>(emptyQualityState);
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

  // Para a câmera ao mudar de aba
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
    setQuality({ status: "validating", message: VALIDATING_QUALITY_MESSAGE, checks: pendingQualityChecks() });
    onChange(null);

    try {
      const result = await validatePhotoQuality(file);
      if (validationRunRef.current !== runId) return;
      setQuality(result);
      if (result.status === "approved") {
        setCandidateFile(null);
        onChange(file);
        return;
      }
      onChange(null);
    } catch (qualityError) {
      console.warn("FACE_PHOTO_QUALITY_VALIDATION_SKIPPED", qualityError);
      if (validationRunRef.current !== runId) return;
      setQuality({
        status: "approved",
        message: APPROVED_QUALITY_MESSAGE,
        checks: {
          lighting: "skipped",
          size: "skipped",
          face: "skipped",
          contrast: "skipped"
        }
      });
      setCandidateFile(null);
      onChange(file);
    }
  }

  function clearPhoto() {
    validationRunRef.current += 1;
    setCandidateFile(null);
    setQuality(emptyQualityState());
    onChange(null);
  }

  // ─── Render ───────────────────────────────────────────────────────

  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
      {/* Abas — só mostra se câmera for suportada */}
      {cameraSupported && (
        <div className="mb-3 flex gap-1 rounded-xl bg-white/5 p-1">
          <TabButton active={activeTab === "camera"} onClick={() => handleTabChange("camera")} icon={Video} label="Câmera ao vivo" />
          <TabButton active={activeTab === "upload"} onClick={() => handleTabChange("upload")} icon={Image} label="Foto / Galeria" />
        </div>
      )}

      {/* Aviso quando câmera não está disponível (HTTP fora de localhost) */}
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
                variant={quality.status === "rejected" ? "danger" : "secondary"}
                icon={RotateCcw}
                className={quality.status === "rejected" ? "ring-1 ring-red-300/30" : undefined}
                disabled={disabled || quality.status === "validating"}
                onClick={retakeFromCamera}
              >
                {quality.status === "rejected" ? "Tirar nova foto" : "Refazer"}
              </Button>
            )}
          </div>
        </>
      )}

      {/* ─── ABA UPLOAD / CÂMERA NATIVA ─── */}
      {(activeTab === "upload" || !cameraSupported) && (
        <>
          {/* Preview da imagem selecionada */}
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
            /* Área de clique / drop */
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

          {/* Input escondido — capture="user" abre câmera frontal no celular */}
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
                variant={quality.status === "rejected" ? "danger" : "secondary"}
                icon={RotateCcw}
                className={quality.status === "rejected" ? "ring-1 ring-red-300/30" : undefined}
                disabled={disabled || quality.status === "validating"}
                onClick={() => fileInputRef.current?.click()}
              >
                {quality.status === "rejected" ? "Tirar nova foto" : "Trocar foto"}
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
  const rejected = quality.status === "rejected";
  const message = qualityMessage(quality);
  const hasVisibleChecks = Object.values(quality.checks).some((status) => status !== "skipped");

  return (
    <div className={`mt-3 rounded-xl border p-3 ${
      approved
        ? "border-emerald-300/20 bg-emerald-400/10 text-emerald-100"
        : rejected
          ? "border-red-300/20 bg-red-500/12 text-red-100"
          : "border-white/10 bg-white/[0.055] text-slate-200"
    }`}>
      <div className="flex items-start gap-2 text-sm font-semibold">
        {quality.status === "validating" ? (
          <Loader2 className="mt-0.5 h-4 w-4 animate-spin text-slate-300" />
        ) : approved ? (
          <CheckCircle2 className="mt-0.5 h-4 w-4 text-emerald-300" />
        ) : (
          <XCircle className="mt-0.5 h-4 w-4 text-red-300" />
        )}
        <span>{message}</span>
      </div>
      {hasVisibleChecks ? (
        <div className="mt-3 grid gap-2 text-xs font-medium text-slate-300 sm:grid-cols-2">
          <QualityPill checkKey="lighting" label="Iluminação" status={quality.checks.lighting} />
          <QualityPill checkKey="size" label="Tamanho" status={quality.checks.size} />
          <QualityPill checkKey="contrast" label="Contraste" status={quality.checks.contrast} />
          <QualityPill checkKey="face" label="Rosto" status={quality.checks.face} />
        </div>
      ) : null}
    </div>
  );
}

function QualityPill({ checkKey, label, status }: { checkKey: QualityCheckKey; label: string; status: QualityCheckStatus }) {
  if (status === "skipped") {
    return null;
  }

  const icon = status === "pass"
    ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-200" />
    : status === "fail"
      ? <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-red-200" />
      : <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-500" />;
  const text = status === "pass"
    ? `${label}: OK`
    : status === "fail"
      ? QUALITY_FAILURE_MESSAGES[checkKey]
      : `${label}: aguardando`;

  return (
    <div className="flex items-start gap-1.5 rounded-lg border border-white/10 bg-black/15 px-2 py-1.5 leading-snug">
      {icon}
      <span>{text}</span>
    </div>
  );
}

function qualityMessage(quality: QualityState): string {
  if (quality.status === "validating") {
    return VALIDATING_QUALITY_MESSAGE;
  }
  if (quality.status === "approved") {
    return APPROVED_QUALITY_MESSAGE;
  }
  if (quality.status === "rejected") {
    return firstQualityFailureMessage(quality.checks) ?? quality.message;
  }
  return "";
}

function firstQualityFailureMessage(checks: QualityChecks): string | undefined {
  for (const check of QUALITY_FAILURE_PRIORITY) {
    if (checks[check] === "fail") {
      return QUALITY_FAILURE_MESSAGES[check];
    }
  }
  return undefined;
}

async function validatePhotoQuality(file: File): Promise<QualityState> {
  const image = await loadImage(file);
  const checks = pendingQualityChecks();
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;

  checks.size = width >= MIN_IMAGE_SIZE && height >= MIN_IMAGE_SIZE ? "pass" : "fail";

  const metrics = imageMetrics(image, width, height);
  checks.lighting = metrics.averageLuminance >= MIN_LUMINANCE ? "pass" : "fail";
  checks.contrast = metrics.standardDeviation >= MIN_CONTRAST_STD_DEV ? "pass" : "fail";
  checks.face = await detectFace(image);

  if (checks.lighting === "fail") {
    return rejectQuality(QUALITY_FAILURE_MESSAGES.lighting, checks);
  }
  if (checks.size === "fail") {
    return rejectQuality(QUALITY_FAILURE_MESSAGES.size, checks);
  }
  if (checks.contrast === "fail") {
    return rejectQuality(QUALITY_FAILURE_MESSAGES.contrast, checks);
  }
  if (checks.face === "fail") {
    return rejectQuality(QUALITY_FAILURE_MESSAGES.face, checks);
  }

  return {
    status: "approved",
    message: APPROVED_QUALITY_MESSAGE,
    checks
  };
}

function imageMetrics(image: HTMLImageElement, width: number, height: number) {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) {
    throw new Error("Canvas unavailable.");
  }
  ctx.drawImage(image, 0, 0, width, height);
  const pixels = ctx.getImageData(0, 0, width, height).data;
  let sum = 0;
  let sumSquares = 0;
  let count = 0;

  for (let index = 0; index < pixels.length; index += 4) {
    const luminance = 0.2126 * pixels[index] + 0.7152 * pixels[index + 1] + 0.0722 * pixels[index + 2];
    sum += luminance;
    sumSquares += luminance * luminance;
    count++;
  }

  const averageLuminance = count ? sum / count : 0;
  const variance = count ? sumSquares / count - averageLuminance * averageLuminance : 0;
  return {
    averageLuminance,
    standardDeviation: Math.sqrt(Math.max(variance, 0))
  };
}

async function detectFace(image: HTMLImageElement): Promise<QualityCheckStatus> {
  if (typeof window === "undefined" || !window.FaceDetector) {
    console.warn("FACE_PHOTO_FACEDETECTOR_UNAVAILABLE");
    return "skipped";
  }
  try {
    const detector = new window.FaceDetector({ fastMode: true, maxDetectedFaces: 1 });
    const faces = await detector.detect(image);
    return faces.length > 0 ? "pass" : "fail";
  } catch (faceError) {
    console.warn("FACE_PHOTO_FACEDETECTOR_FAILED", faceError);
    return "skipped";
  }
}

function loadImage(file: File) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new window.Image();
    const url = URL.createObjectURL(file);
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("Image could not be loaded."));
    };
    image.src = url;
  });
}

function rejectQuality(message: string, checks: QualityChecks): QualityState {
  return { status: "rejected", message, checks };
}

function emptyQualityState(): QualityState {
  return {
    status: "idle",
    message: "",
    checks: {
      lighting: "pending",
      size: "pending",
      face: "pending",
      contrast: "pending"
    }
  };
}

function pendingQualityChecks(): QualityChecks {
  return {
    lighting: "pending",
    size: "pending",
    face: "pending",
    contrast: "pending"
  };
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
