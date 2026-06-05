package br.com.sport.accesscontrol.analytics;

import java.time.Instant;
import java.util.UUID;

public record PresenceItem(
        String personType,
        UUID personId,
        String personName,
        String personCpf,
        String areaName,
        Instant entryTime,
        long minutesInside
) {}
