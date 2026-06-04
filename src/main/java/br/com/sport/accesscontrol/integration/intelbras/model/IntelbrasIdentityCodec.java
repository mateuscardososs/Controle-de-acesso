package br.com.sport.accesscontrol.integration.intelbras.model;

import br.com.sport.accesscontrol.common.PersonType;

import java.util.Locale;
import java.util.UUID;

public final class IntelbrasIdentityCodec {

    private static final long CARD_MIN = 100_000L;
    private static final long CARD_RANGE = 899_999L;

    private IntelbrasIdentityCodec() {
    }

    public static IntelbrasIdentity resolve(Strategy requestedStrategy, PersonType personType, UUID personId, String document) {
        var strategy = requestedStrategy == null ? Strategy.DOCUMENT : requestedStrategy;
        if (strategy == Strategy.DOCUMENT) {
            var documentDigits = digits(document);
            if (!documentDigits.isBlank()) {
                // SS 5531 MF W accepts and preserves 11-digit CPFs, including a leading zero.
                // For people without a real physical card, UserID and CardNo must both be the full CPF.
                return new IntelbrasIdentity(Strategy.DOCUMENT, documentDigits, documentDigits);
            }
            return new IntelbrasIdentity(Strategy.DOCUMENT, "", "");
        }
        if (strategy == Strategy.SHORT_ALPHANUMERIC) {
            return new IntelbrasIdentity(Strategy.SHORT_ALPHANUMERIC, shortAlphanumericUserId(personType, personId), shortNumeric(personId));
        }
        return new IntelbrasIdentity(Strategy.SHORT_NUMERIC, shortNumeric(personId), shortNumeric(personId));
    }

    public static String shortAlphanumericUserId(PersonType personType, UUID personId) {
        if (personId == null) {
            return "";
        }
        return prefix(personType) + personId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    public static String shortNumeric(UUID personId) {
        if (personId == null) {
            return "";
        }
        var mixed = personId.getMostSignificantBits() ^ Long.rotateLeft(personId.getLeastSignificantBits(), 17);
        return Long.toString(CARD_MIN + Math.floorMod(mixed, CARD_RANGE));
    }

    public static String cardNoFromDocument(String document) {
        var documentDigits = digits(document);
        if (documentDigits.length() != 11) {
            return "";
        }
        return documentDigits;
    }

    private static String prefix(PersonType personType) {
        if (personType == PersonType.GUEST) {
            return "G";
        }
        if (personType == PersonType.EMPLOYEE) {
            return "E";
        }
        return "U";
    }

    public static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public enum Strategy {
        DOCUMENT,
        SHORT_NUMERIC,
        SHORT_ALPHANUMERIC
    }

    public record IntelbrasIdentity(
            Strategy strategy,
            String userId,
            String cardNo
    ) {
    }
}
