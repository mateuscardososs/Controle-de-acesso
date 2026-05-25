package br.com.sport.accesscontrol.common;

public final class CpfValidator {

    public static final String INVALID_CPF_MESSAGE = "CPF inválido. Verifique os números informados.";

    private CpfValidator() {
    }

    public static String normalizeOrThrow(String value) {
        var digits = onlyDigits(value);
        if (!isValidDigits(digits)) {
            throw new IllegalArgumentException(INVALID_CPF_MESSAGE);
        }
        return digits;
    }

    public static boolean isValid(String value) {
        return isValidDigits(onlyDigits(value));
    }

    public static String onlyDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static boolean isValidDigits(String digits) {
        if (digits.length() != 11 || repeatedDigits(digits)) {
            return false;
        }
        return checkDigit(digits, 9) == digits.charAt(9) - '0'
                && checkDigit(digits, 10) == digits.charAt(10) - '0';
    }

    private static boolean repeatedDigits(String digits) {
        for (int index = 1; index < digits.length(); index++) {
            if (digits.charAt(index) != digits.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    private static int checkDigit(String digits, int length) {
        var sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (digits.charAt(index) - '0') * (length + 1 - index);
        }
        var remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
