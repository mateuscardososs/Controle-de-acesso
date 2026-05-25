"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Camera, RotateCcw, Video } from "lucide-react";
import { Button } from "@/src/components/ui/Button";

type CameraCaptureProps = {
  value: File | null;
  onChange: (file: File | null) => void;
  disabled?: boolean;
};

export function CameraCapture({ value, onChange, disabled }: CameraCaptureProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [active, setActive] = useState(false);
  const [error, setError] = useState("");
  const preview = useMemo(() => (value ? URL.createObjectURL(value) : ""), [value]);

  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview);
    };
  }, [preview]);

  useEffect(() => {
    return () => stopCamera();
  }, []);

  async function startCamera() {
    setError("");
    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error("Câmera indisponível neste navegador.");
      }
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "user", width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setActive(true);
    } catch (cameraError) {
      setError(cameraAccessErrorMessage(cameraError));
      setActive(false);
    }
  }

  function stopCamera() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setActive(false);
  }

  function capture() {
    const video = videoRef.current;
    if (!video) return;
    const width = video.videoWidth || 960;
    const height = video.videoHeight || 720;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    if (!context) {
      setError("Não foi possível capturar a foto.");
      return;
    }
    context.drawImage(video, 0, 0, width, height);
    canvas.toBlob((blob) => {
      if (!blob) {
        setError("Não foi possível capturar a foto.");
        return;
      }
      onChange(new File([blob], "foto-visitante.jpg", { type: "image/jpeg" }));
      stopCamera();
    }, "image/jpeg", 0.92);
  }

  function retake() {
    onChange(null);
    void startCamera();
  }

  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.045] p-3">
      <div className="overflow-hidden rounded-xl border border-white/10 bg-slate-950">
        {preview ? (
          <img src={preview} alt="Foto capturada" className="h-64 w-full object-cover" />
        ) : (
          <video ref={videoRef} playsInline muted className="h-64 w-full object-cover" />
        )}
      </div>

      {error ? (
        <div className="mt-3 flex items-start gap-2 rounded-xl border border-red-300/20 bg-red-500/12 px-3 py-2 text-sm font-medium text-red-100">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}

      <div className="mt-3 flex flex-wrap gap-2">
        {!preview && !active ? (
          <Button type="button" variant="secondary" icon={Video} disabled={disabled} onClick={startCamera}>
            Ativar câmera
          </Button>
        ) : null}
        {!preview && active ? (
          <Button type="button" icon={Camera} disabled={disabled} onClick={capture}>
            Tirar foto
          </Button>
        ) : null}
        {preview ? (
          <Button type="button" variant="secondary" icon={RotateCcw} disabled={disabled} onClick={retake}>
            Refazer
          </Button>
        ) : null}
      </div>
    </div>
  );
}

function cameraAccessErrorMessage(error: unknown) {
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
