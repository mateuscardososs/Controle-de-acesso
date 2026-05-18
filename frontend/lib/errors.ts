import { AxiosError } from "axios";

type ApiError = {
  error?: string;
  details?: string[];
};

export function apiErrorMessage(error: unknown, fallback: string) {
  const axiosError = error as AxiosError<ApiError>;
  const details = axiosError.response?.data?.details;
  if (details?.length) {
    return details.join(" ");
  }
  return axiosError.response?.data?.error ?? fallback;
}
