"use client";

/* eslint-disable @next/next/no-img-element */

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Camera, Image, RotateCcw, Upload, Video, X } from "lucide-react";
import { Button } from "@/src/components/ui/Button";

type CameraCaptureProps = {
  value: File | null;
  onChange: (file: File | null) => void;
  disabled?: boolean;
};

type ActiveTab = "camera" | "upload";

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const ALLOWED_EXTENSIONS = ["jpg", "jpeg", "png", "webp"];
const MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB — alinhado com FaceStorageService

function isCameraAvailable(): boolean {
  if (typeof window === "undefined") return false;
  const isSecure = window.location.protocol === "https:" || window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";
  return isSecure && typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
}

export function CameraCapture({ value, onChange, disabled }: CameraCaptureProps) {
  const cameraSupported = useMemo(() => isCameraAvailable(), []);
  const [activeTab, setActiveTab] = useState<ActiveTab>(cameraSupported ? "camera" : "upload");
  const [cameraActive, setCameraActive] = useState(false);
  const [error, setError] = useState("");
  const [uploadError, setUploadError] = useState("");
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const preview = useMemo(() => (value ? URL.createObjectURL(value) : ""), [value]);

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
        onChange(new File([blob], "foto-visitante.jpg", { type: "image/jpeg" }));
        stopCamera();
      },
      "image/jpeg",
      0.92
    );
  }

  function retakeFromCamera() {
    onChange(null);
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

    onChange(file);
    event.target.value = "";
  }

  function removeUpload() {
    onChange(null);
    setUploadError("");
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
          <div className="overflow-hidden rounded-xl border border-white/10 bg-slate-950">
            {preview ? (
              <img src={preview} alt="Foto capturada" className="h-64 w-full object-cover" />
            ) : (
              <video ref={videoRef} playsInline muted className="h-64 w-full object-cover" />
            )}
          </div>

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
              <Button type="button" variant="secondary" icon={RotateCcw} disabled={disabled} onClick={retakeFromCamera}>
                Refazer
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
              <Button type="button" variant="secondary" icon={RotateCcw} disabled={disabled} onClick={() => fileInputRef.current?.click()}>
                Trocar foto
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
