import { api } from "@/lib/api";

export type CpfValidationResponse = {
  found: boolean;
  fullName: string | null;
  invitedLounge: string | null;
  message: string;
};

export type CpfCheckinResponse = {
  success: boolean;
  fullName: string | null;
  invitedLounge: string | null;
  message: string;
};

export const checkinService = {
  async validateCpf(cpf: string) {
    const { data } = await api.post<CpfValidationResponse>("/api/public/guests/validate-cpf", { cpf });
    return data;
  },
  async completeRegistration(cpf: string, facePhoto: string) {
    const { data } = await api.post<CpfCheckinResponse>(
      "/api/public/guests/complete-registration",
      { cpf, facePhoto }
    );
    return data;
  }
};
