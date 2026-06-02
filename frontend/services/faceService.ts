import { api } from "@/lib/api";

export type FaceValidationChecks = {
  faceDetected: boolean;
  singleFace: boolean;
  brightnessOk: boolean;
  sharpnessOk: boolean;
  contrastOk: boolean;
  centeredOk: boolean;
  sizeOk: boolean;
  faceFullyVisibleOk: boolean;
  compressedSizeBytes: number;
  maxAllowedBytes: number;
};

export type FaceValidationResponse = {
  approved: boolean;
  message: string;
  checks: FaceValidationChecks;
};

export const faceService = {
  /**
   * Validates a face photo against the real backend pipeline WITHOUT saving it.
   * The backend is the single source of truth for the "approved" decision.
   */
  async validate(file: File): Promise<FaceValidationResponse> {
    const formData = new FormData();
    formData.append("file", file);
    const { data } = await api.post<FaceValidationResponse>("/api/public/face/validate", formData);
    return data;
  }
};
