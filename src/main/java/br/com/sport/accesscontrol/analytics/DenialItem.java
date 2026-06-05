package br.com.sport.accesscontrol.analytics;

import java.time.Instant;
import java.util.UUID;

public record DenialItem(
        UUID id,
        Instant eventTime,
        String personName,
        String personCpf,
        String deviceName,
        String areaName,
        String decisionReason,
        String releaseMethod
) {}
