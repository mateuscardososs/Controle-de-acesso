package br.com.sport.accesscontrol.appconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LoungeConfig {

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
        return lounges;
    }

    public boolean isValid(String value) {
        var officialName = officialName(value);
        return officialName != null && lounges.contains(officialName);
    }

    private static Map<String, String> officialLoungesByKey() {
        var values = new LinkedHashMap<String, String>();
        OFFICIAL_LOUNGES.forEach(lounge -> values.put(normalizeKey(lounge), lounge));
        return values;
    }

    private static String officialName(String value) {
        return OFFICIAL_LOUNGES_BY_KEY.get(normalizeKey(value));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
