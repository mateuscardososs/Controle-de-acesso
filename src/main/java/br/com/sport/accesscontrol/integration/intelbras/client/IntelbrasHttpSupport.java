package br.com.sport.accesscontrol.integration.intelbras.client;

import java.net.URI;
import java.util.regex.Pattern;

public final class IntelbrasHttpSupport {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private IntelbrasHttpSupport() {
    }

    public static URI uri(String host, String pathAndQuery) {
        var normalizedHost = normalizeHost(host);
        var normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return URI.create(normalizedHost + normalizedPath);
    }

    public static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Intelbras host is required.");
        }
        var trimmed = host.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.replaceAll("/+$", "");
        }
        return "http://" + trimmed.replaceAll("/+$", "");
    }

    public static String digestUri(URI uri) {
        var value = uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            value += "?" + uri.getRawQuery();
        }
        return value;
    }

    public static String maskHost(String host) {
        if (host == null || host.isBlank()) {
            return "<empty-host>";
        }
        var normalized = host.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        var matcher = IPV4.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1) + "." + matcher.group(2) + ".*.*";
        }
        if (normalized.length() <= 6) {
            return "***";
        }
        return normalized.substring(0, 3) + "***" + normalized.substring(normalized.length() - 3);
    }
}
