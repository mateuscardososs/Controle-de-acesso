export function onlyCpfDigits(value?: string | null) {
  return value?.replace(/\D/g, "").slice(0, 11) ?? "";
}

export function formatCpfInput(value?: string | null) {
  const digits = onlyCpfDigits(value);
  if (digits.length <= 3) return digits;
  if (digits.length <= 6) return `${digits.slice(0, 3)}.${digits.slice(3)}`;
  if (digits.length <= 9) return `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6)}`;
  return `${digits.slice(0, 3)}.${digits.slice(3, 6)}.${digits.slice(6, 9)}-${digits.slice(9)}`;
}

export function formatCpfDisplay(value?: string | null) {
  if (!value) return "Não informado";
  const digits = onlyCpfDigits(value);
  if (digits.length !== 11) return value;
  return formatCpfInput(digits);
}

export function isValidCpf(value?: string | null) {
  const digits = onlyCpfDigits(value);
  if (digits.length !== 11) return false;
  if (/^(\d)\1{10}$/.test(digits)) return false;

  const numbers = digits.split("").map(Number);
  const firstDigit = cpfCheckDigit(numbers.slice(0, 9));
  const secondDigit = cpfCheckDigit(numbers.slice(0, 10));
  return firstDigit === numbers[9] && secondDigit === numbers[10];
}

function cpfCheckDigit(numbers: number[]) {
  const sum = numbers.reduce((total, number, index) => total + number * (numbers.length + 1 - index), 0);
  const remainder = (sum * 10) % 11;
  return remainder === 10 ? 0 : remainder;
}
