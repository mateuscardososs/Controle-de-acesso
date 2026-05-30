import { api } from "@/lib/api";

export type GuestStatus = "INVITED" | "PENDING_REGISTRATION" | "COMPLETED" | "EXPIRED" | "CANCELLED";
export type SyncStatus = "NOT_REQUIRED" | "PENDING_SYNC" | "SYNCING" | "SYNCED_WITH_WARNINGS" | "SYNCED" | "SYNC_FAILED";

export type Guest = {
  id: string;
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  company?: string;
  visitReason: string;
  hostName: string;
  visitStart: string;
  visitEnd: string;
  invitedDay?: string;
  invitedLounge?: string;
  status: GuestStatus;
  facePhotoUrl?: string;
  invitedAt?: string;
  completedAt?: string;
  inviteToken?: string;
  inviteUrl?: string;
  inviteExpiresAt?: string;
  emailDeliveryStatus?: "SENT" | "SKIPPED" | "FAILED" | string;
  emailDeliveryMessage?: string;
  syncStatus?: SyncStatus;
  lastSyncAt?: string;
  lastSyncError?: string;
  syncAttempts?: number;
  syncTargetCount?: number;
  syncSuccessCount?: number;
  syncFailedCount?: number;
  syncSkippedCount?: number;
  accessApprovedEmailSentAt?: string;
  accessApprovedEmailStatus?: "SENT" | "SKIPPED" | "FAILED" | string;
  accessApprovedEmailMessage?: string;
  allowedAreaIds?: string[];
  allowedAreaNames?: string[];
  displayAllowedAreas?: string | null;
};

export type GuestPayload = {
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  company?: string;
  visitReason: string;
  hostName: string;
  visitStart: string;
  visitEnd: string;
  invitedDay?: string;
  invitedLounge?: string;
  status?: GuestStatus;
};

export type GuestCleanupPayload = {
  mode: "CANCELLED" | "FAILED" | "TEST_RECORDS" | "ALL";
  confirmationPhrase?: string;
};

export type GuestCleanupResponse = {
  removedCount: number;
  message: string;
};

export type PublicGuestRegistration = {
  id: string;
  fullName: string;
  company?: string;
  visitReason: string;
  hostName: string;
  visitStart: string;
  visitEnd: string;
  invitedDay?: string;
  invitedLounge?: string;
  status: GuestStatus;
  requiresFacePhoto: boolean;
};

export type PublicVisitorRegistrationPayload = {
  fullName: string;
  cpf: string;
  phone: string;
  email?: string;
  invitedDay: string;
  invitedLounge: string;
  facePhoto: File;
};

export type AdminVisitorRegistrationPayload = PublicVisitorRegistrationPayload;

export type PublicVisitorRegistrationResponse = {
  id: string;
  fullName: string;
  status: GuestStatus;
  message: string;
  facePhotoReceived: boolean;
};

export const guestService = {
  async list() {
    const { data } = await api.get<Guest[]>("/api/guests");
    return data;
  },
  async today() {
    const { data } = await api.get<Guest[]>("/api/guests", { params: { scope: "today" } });
    return data;
  },
  async create(payload: GuestPayload) {
    const { data } = await api.post<Guest>("/api/guests", payload);
    return data;
  },
  async createVisitorRegistration(payload: AdminVisitorRegistrationPayload) {
    const formData = new FormData();
    formData.append("fullName", payload.fullName);
    formData.append("cpf", payload.cpf);
    formData.append("phone", payload.phone);
    if (payload.email) formData.append("email", payload.email);
    formData.append("invitedDay", payload.invitedDay);
    formData.append("invitedLounge", payload.invitedLounge);
    formData.append("facePhoto", payload.facePhoto);
    const { data } = await api.post<Guest>("/api/guests/visitor-registration", formData);
    return data;
  },
  async update(id: string, payload: GuestPayload) {
    const { data } = await api.put<Guest>(`/api/guests/${id}`, payload);
    return data;
  },
  async cancel(id: string) {
    const { data } = await api.patch<Guest>(`/api/guests/${id}/cancel`);
    return data;
  },
  async resendInvite(id: string) {
    const { data } = await api.post<Guest>(`/api/guests/${id}/resend-invite`);
    return data;
  },
  async syncGuest(id: string) {
    const { data } = await api.post<Guest>(`/api/guests/${id}/sync`);
    return data;
  },
  async retryIntelbrasSync(id: string) {
    const { data } = await api.post<{ status: string; type: string; id: string }>(`/api/integration/retry/guest/${id}`);
    return data;
  },
  async cleanup(payload: GuestCleanupPayload) {
    const { data } = await api.delete<GuestCleanupResponse>("/api/guests/cleanup", { data: payload });
    return data;
  },
  async publicRegistration(token: string) {
    const { data } = await api.get<PublicGuestRegistration>(`/api/guest-registration/${token}`);
    return data;
  },
  async completeRegistration(token: string, payload: { phone?: string; company?: string; facePhoto: File }) {
    const formData = new FormData();
    if (payload.phone) formData.append("phone", payload.phone);
    if (payload.company) formData.append("company", payload.company);
    formData.append("facePhoto", payload.facePhoto);
    const { data } = await api.post<Guest>(`/api/guest-registration/${token}/complete`, formData);
    return data;
  },
  async publicVisitorRegistration(payload: PublicVisitorRegistrationPayload) {
    const formData = new FormData();
    formData.append("fullName", payload.fullName);
    formData.append("cpf", payload.cpf);
    formData.append("phone", payload.phone);
    if (payload.email) formData.append("email", payload.email);
    formData.append("invitedDay", payload.invitedDay);
    formData.append("invitedLounge", payload.invitedLounge);
    formData.append("facePhoto", payload.facePhoto);
    const { data } = await api.post<PublicVisitorRegistrationResponse>("/api/public/visitor-registration", formData);
    return data;
  }
};
