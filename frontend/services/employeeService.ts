import { api } from "@/lib/api";

export type Employee = {
  id: string;
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  registrationNumber?: string;
  cardNo?: string;
  facePhotoUrl?: string;
  userId?: string;
  role?: "ADMIN" | "HR" | "SECURITY_VIEWER";
  status: "ACTIVE" | "INACTIVE" | "BLOCKED";
  accessValidFrom?: string;
  accessValidUntil?: string;
  syncStatus?: "NOT_REQUIRED" | "PENDING_SYNC" | "SYNCING" | "SYNCED_WITH_WARNINGS" | "SYNCED" | "SYNC_FAILED";
  lastSyncAt?: string;
  lastSyncError?: string;
  syncAttempts?: number;
  syncTargetCount?: number;
  syncSuccessCount?: number;
  syncFailedCount?: number;
  syncSkippedCount?: number;
  allowedAreaIds?: string[];
  allowedAreaNames?: string[];
  displayAllowedAreas?: string | null;
  fullAccess?: boolean;
};

export type EmployeePayload = {
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  registrationNumber?: string;
  cardNo?: string;
  facePhoto?: File | null;
  password?: string;
  role?: Employee["role"];
  status: Employee["status"];
};

export const employeeService = {
  async list() {
    const { data } = await api.get<Employee[]>("/api/employees");
    return data;
  },
  async create(payload: EmployeePayload) {
    if (payload.facePhoto) {
      const formData = new FormData();
      formData.append("fullName", payload.fullName);
      formData.append("cpf", payload.cpf);
      if (payload.email) formData.append("email", payload.email);
      if (payload.phone) formData.append("phone", payload.phone);
      if (payload.registrationNumber) formData.append("registrationNumber", payload.registrationNumber);
      if (payload.cardNo) formData.append("cardNo", payload.cardNo);
      if (payload.password) formData.append("password", payload.password);
      if (payload.role) formData.append("role", payload.role);
      formData.append("status", payload.status);
      formData.append("facePhoto", payload.facePhoto);
      const { data } = await api.post<Employee>("/api/employees", formData);
      return data;
    }
    const { data } = await api.post<Employee>("/api/employees", payload);
    return data;
  },
  async sync(id: string) {
    const { data } = await api.post<Employee>(`/api/employees/${id}/sync`);
    return data;
  },
  async deactivate(id: string) {
    const { data } = await api.patch<Employee>(`/api/employees/${id}/deactivate`);
    return data;
  }
};
