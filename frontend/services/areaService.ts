import { api } from "@/lib/api";

export type Area = {
  id: string;
  name: string;
  description?: string;
  active: boolean;
};

export type AreaPayload = {
  name: string;
  description?: string;
  active?: boolean;
};

export const areaService = {
  async list() {
    const { data } = await api.get<Area[]>("/api/areas");
    return data;
  },
  async create(payload: AreaPayload) {
    const { data } = await api.post<Area>("/api/areas", payload);
    return data;
  }
};
