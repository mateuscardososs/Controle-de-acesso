import { AxiosError } from "axios";

type ApiError = {
  error?: string;
  details?: string[];
};

export function apiErrorMessage(error: unknown, fallback: string) {
  const axiosError = error as AxiosError<ApiError>;
  if (!axiosError.isAxiosError && error instanceof Error) {
    return error.message;
  }
  if (axiosError.code === "ERR_NETWORK" || !axiosError.response) {
    return "Erro de conexão ou CORS. Verifique se o backend está acessível e se a origem do frontend foi liberada.";
  }
  if (axiosError.response.status === 413) {
    return "Imagem muito grande. Envie uma foto menor e tente novamente.";
  }
  if (axiosError.response.status >= 500) {
    return "Erro interno ao processar cadastro. Tente novamente em instantes.";
  }
  const details = axiosError.response?.data?.details;
  if (details?.length) {
    return details.join(" ");
  }
  return axiosError.response?.data?.error ?? fallback;
}
