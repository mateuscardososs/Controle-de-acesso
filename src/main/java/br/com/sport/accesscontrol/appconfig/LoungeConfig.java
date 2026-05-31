package br.com.sport.accesscontrol.appconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LoungeConfig {

    /** Sentinel value for the collaborator lounge — always available regardless of app.lounges config. */
    public static final String COLLABORATOR_LOUNGE = "Colaborador";

    public static final List<String> OFFICIAL_LOUNGES = List.of(
            "Front 1",
            "Front 2",
            "Institucional 1",
            "Institucional Vereadores"
    );
    private static final Map<String, String> OFFICIAL_LOUNGES_BY_KEY = officialLoungesByKey();

    private final List<String> lounges;

    public LoungeConfig(@Value("${app.lounges:Front 1,Front 2,Institucional 1,Institucional Vereadores}") List<String> lounges) {
        var configuredLounges = lounges.stream()
                .map(LoungeConfig::officialName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        this.lounges = configuredLounges.isEmpty() ? OFFICIAL_LOUNGES : configuredLounges;
    }

    public List<String> getLounges() {
        // Colaborador is always appended — independently of the app.lounges property and Docker env vars
        if (lounges.contains(COLLABORATOR_LOUNGE)) {
            return lounges;
        }
        var result = new java.util.ArrayList<>(lounges);
        result.add(COLLABORATOR_LOUNGE);
        return java.util.Collections.unmodifiableList(result);
    }

    public boolean isValid(String value) {
        if (COLLABORATOR_LOUNGE.equalsIgnoreCase(value == null ? "" : value.trim())) {
            return true;
        }
        var officialName = officialName(value);
        return officialName != null && lounges.contains(officialName);
    }

    private static Map<String, String> officialLoungesByKey() {
        var values = new LinkedHashMap<String, String>();
        OFFICIAL_LOUNGES.forEach(lounge -> values.put(normalizeKey(lounge), lounge));
        values.put(normalizeKey("Front 3"), "Front 2");
        values.put(normalizeKey("Instrucional 1"), "Institucional 1");
        values.put(normalizeKey("Instrucional Vereadores"), "Institucional Vereadores");
        return values;
    }

    public String canonicalName(String value) {
        return canonicalLoungeName(value);
    }

    public static String canonicalLoungeName(String value) {
        if (COLLABORATOR_LOUNGE.equalsIgnoreCase(value == null ? "" : value.trim())) {
            return COLLABORATOR_LOUNGE;
        }
        return officialName(value);
    }

    private static String officialName(String value) {
        return OFFICIAL_LOUNGES_BY_KEY.get(normalizeKey(value));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
