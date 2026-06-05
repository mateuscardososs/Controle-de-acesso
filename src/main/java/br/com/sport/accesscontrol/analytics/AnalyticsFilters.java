package br.com.sport.accesscontrol.analytics;

import java.time.Instant;
import java.util.UUID;

public record AnalyticsFilters(
        Instant from,
        Instant to,
        UUID deviceId,
        UUID areaId,
        String personType,
        String releaseMethod
) {
    static final String ZONE_ID = "America/Recife";
}
