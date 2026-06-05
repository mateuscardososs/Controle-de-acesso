package br.com.sport.accesscontrol.analytics;

import java.util.UUID;

public record AreaStatsItem(
        UUID id,
        String name,
        long totalAccesses
) {}
