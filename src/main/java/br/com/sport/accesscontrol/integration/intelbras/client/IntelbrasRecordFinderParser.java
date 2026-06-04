package br.com.sport.accesscontrol.integration.intelbras.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class IntelbrasRecordFinderParser {

    private static final Pattern INDEXED_FIELD = Pattern.compile("^[A-Za-z_]+\\[(\\d+)]\\.(.+)$");

    private IntelbrasRecordFinderParser() {
    }

    public static Map<String, String> parseKeyValues(String body) {
        var values = new LinkedHashMap<String, String>();
        if (body == null || body.isBlank()) {
            return values;
        }
        for (String line : body.split("\\R")) {
            var trimmed = line.trim();
            if (trimmed.isBlank() || !trimmed.contains("=")) {
                continue;
            }
            var separator = trimmed.indexOf('=');
            values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return values;
    }

    public static List<Map<String, Object>> parseRecords(String body) {
        var recordsByIndex = new TreeMap<Integer, Map<String, Object>>();
        parseKeyValues(body).forEach((key, value) -> {
            var matcher = INDEXED_FIELD.matcher(key);
            if (!matcher.matches()) {
                return;
            }
            var index = Integer.parseInt(matcher.group(1));
            var field = matcher.group(2);
            recordsByIndex.computeIfAbsent(index, ignored -> new LinkedHashMap<>()).put(field, parseScalar(field, value));
        });
        return new ArrayList<>(recordsByIndex.values());
    }

    private static Object parseScalar(String field, String value) {
        if (value == null) {
            return null;
        }
        if ("UserID".equalsIgnoreCase(field) || "CardNo".equalsIgnoreCase(field)) {
            return value;
        }
        if (value.matches("-?\\d+")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }
}
