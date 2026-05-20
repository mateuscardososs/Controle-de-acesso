package br.com.sport.accesscontrol.integration.intelbras.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class IntelbrasDigestAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IntelbrasDigestAuth() {
    }

    static Map<String, String> parseChallenge(String header) {
        if (header == null || header.isBlank()) {
            return Map.of();
        }
        var value = header.trim();
        if (value.regionMatches(true, 0, "Digest", 0, "Digest".length())) {
            value = value.substring("Digest".length()).trim();
        }
        var parts = new LinkedHashMap<String, String>();
        var current = new StringBuilder();
        var inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            if (character == '"') {
                inQuotes = !inQuotes;
            }
            if (character == ',' && !inQuotes) {
                addPart(parts, current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        addPart(parts, current.toString());
        return parts;
    }

    static String authorizationHeader(
            String method,
            URI uri,
            String username,
            String password,
            Map<String, String> challenge
    ) {
        var realm = challenge.get("realm");
        var nonce = challenge.get("nonce");
        if (realm == null || nonce == null) {
            throw new IntelbrasIntegrationException("Intelbras Digest challenge is incomplete.");
        }

        var qop = firstQop(challenge.get("qop"));
        var algorithm = challenge.getOrDefault("algorithm", "MD5");
        var digestUri = IntelbrasHttpSupport.digestUri(uri);
        var nc = "00000001";
        var cnonce = cnonce();
        var ha1 = md5Lower(username + ":" + realm + ":" + password);
        if ("MD5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5Lower(ha1 + ":" + nonce + ":" + cnonce);
        }
        var ha2 = md5Lower(method + ":" + digestUri);
        var response = qop == null
                ? md5Lower(ha1 + ":" + nonce + ":" + ha2)
                : md5Lower(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        var header = new StringBuilder("Digest ");
        appendQuoted(header, "username", username);
        appendQuoted(header, "realm", realm);
        appendQuoted(header, "nonce", nonce);
        appendQuoted(header, "uri", digestUri);
        appendQuoted(header, "response", response);
        if (algorithm != null && !algorithm.isBlank()) {
            appendToken(header, "algorithm", algorithm);
        }
        if (challenge.containsKey("opaque")) {
            appendQuoted(header, "opaque", challenge.get("opaque"));
        }
        if (qop != null) {
            appendToken(header, "qop", qop);
            appendToken(header, "nc", nc);
            appendQuoted(header, "cnonce", cnonce);
        }
        if (header.substring(header.length() - 2).equals(", ")) {
            header.setLength(header.length() - 2);
        }
        return header.toString();
    }

    private static void addPart(Map<String, String> parts, String rawPart) {
        var part = rawPart.trim();
        if (part.isBlank() || !part.contains("=")) {
            return;
        }
        var separator = part.indexOf('=');
        var key = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        var value = part.substring(separator + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        parts.put(key, value);
    }

    private static String firstQop(String qop) {
        if (qop == null || qop.isBlank()) {
            return null;
        }
        for (String part : qop.split(",")) {
            if ("auth".equalsIgnoreCase(part.trim())) {
                return "auth";
            }
        }
        return qop.split(",")[0].trim();
    }

    private static void appendQuoted(StringBuilder header, String key, String value) {
        header.append(key).append("=\"").append(value == null ? "" : value.replace("\"", "")).append("\", ");
    }

    private static void appendToken(StringBuilder header, String key, String value) {
        header.append(key).append('=').append(value).append(", ");
    }

    private static String cnonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String md5Lower(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is not available.", exception);
        }
    }
}
