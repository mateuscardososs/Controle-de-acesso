package br.com.sport.accesscontrol.analytics;

import java.time.Instant;
import java.util.UUID;

public record ControllerStatsItem(
        UUID id,
        String name,
        String areaName,
        String status,
        Instant lastSeenAt,
        long communicationFailures,
        long totalAccesses,
        long denials,
        Instant lastEvent
) {}
