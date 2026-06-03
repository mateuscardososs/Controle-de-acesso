package br.com.sport.accesscontrol.integration.sync;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Tolerant String mapping for {@link SyncStatus}. Persists the enum name (identical to the previous
 * {@code @Enumerated(EnumType.STRING)}), but on read an unknown/legacy DB value (e.g. the old
 * {@code "PENDING"}) maps to a safe, recoverable default instead of throwing — so a single bad row
 * can never break hydration of a whole list query (GET /api/guests) again. The data is also healed
 * by migration V20; this converter is defence-in-depth against future drift.
 */
@Converter
public class SyncStatusConverter implements AttributeConverter<SyncStatus, String> {

    private static final Logger log = LoggerFactory.getLogger(SyncStatusConverter.class);

    @Override
    public String convertToDatabaseColumn(SyncStatus attribute) {
        return attribute == null ? SyncStatus.NOT_REQUIRED.name() : attribute.name();
    }

    @Override
    public SyncStatus convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return SyncStatus.NOT_REQUIRED;
        }
        var value = dbValue.trim();
        try {
            return SyncStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            var mapped = legacyOrDefault(value);
            log.warn("SYNC_STATUS_UNKNOWN_VALUE db_value={} mapped_to={} — valor legado/inválido normalizado em leitura",
                    value, mapped);
            return mapped;
        }
    }

    private SyncStatus legacyOrDefault(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "PENDING", "PENDING_SYNC" -> SyncStatus.PENDING_SYNC;
            case "WARNING", "WARNINGS", "PARTIAL", "PARTIAL_SYNC" -> SyncStatus.SYNCED_WITH_WARNINGS;
            case "FAILED", "ERROR" -> SyncStatus.SYNC_FAILED;
            case "OK", "DONE" -> SyncStatus.SYNCED;
            // Default recuperável: o reaper/worker reprocessa em vez de travar a tela.
            default -> SyncStatus.PENDING_SYNC;
        };
    }
}
