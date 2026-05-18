import { api } from "@/lib/api";

export type Employee = {
  id: string;
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  registrationNumber?: string;
  status: "ACTIVE" | "INACTIVE" | "BLOCKED";
};

export type EmployeePayload = {
  fullName: string;
  cpf: string;
  email?: string;
  phone?: string;
  registrationNumber?: string;
  status: Employee["status"];
};

export const employeeService = {
  async list() {
    const { data } = await api.get<Employee[]>("/api/employees");
    return data;
  },
  async create(payload: EmployeePayload) {
    const { data } = await api.post<Employee>("/api/employees", payload);
    return data;
  }
};
