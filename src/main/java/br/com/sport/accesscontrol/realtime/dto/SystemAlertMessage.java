package br.com.sport.accesscontrol.realtime.dto;

import java.time.Instant;
import java.util.UUID;

public record SystemAlertMessage(
        UUID id,
        Severity severity,
        String title,
        String message,
        String source,
        Instant createdAt
) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    public static SystemAlertMessage warning(String title, String message, String source) {
        return new SystemAlertMessage(UUID.randomUUID(), Severity.WARNING, title, message, source, Instant.now());
    }
}
