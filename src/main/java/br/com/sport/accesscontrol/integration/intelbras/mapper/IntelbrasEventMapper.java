package br.com.sport.accesscontrol.integration.intelbras.mapper;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.events.AccessEventType;
import br.com.sport.accesscontrol.events.AccessResult;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class IntelbrasEventMapper {

    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public IntelbrasAccessControlCardRecord parseAccessControlCardRec(Map<String, Object> payload) {
        return new IntelbrasAccessControlCardRecord(
                text(payload, "CardName"),
                text(payload, "UserID"),
                text(payload, "Status"),
                text(payload, "Type"),
                parseTime(text(payload, "CreateTime")),
                text(payload, "URL"),
                text(payload, "ErrorCode"),
                text(payload, "Door"),
                text(payload, "ReaderID"),
                payload
        );
    }

    public NormalizedAccessEvent normalizeAccessControlCardRec(
            Map<String, Object> payload,
            Device device,
            IntelbrasPersonIdentity person
    ) {
        var record = parseAccessControlCardRec(payload);
        var identity = person == null ? fallbackIdentity(record) : person;
        return new NormalizedAccessEvent(
                identity.personType(),
                identity.personId(),
                device == null ? null : device.getId(),
                eventType(record),
                accessResult(record),
                record.createTime() == null ? Instant.now() : record.createTime(),
                "INTELBRAS_REAL",
                payload
        );
    }

    private IntelbrasPersonIdentity fallbackIdentity(IntelbrasAccessControlCardRecord record) {
        var source = record.userId() == null || record.userId().isBlank() ? record.cardName() : record.userId();
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
        return new IntelbrasPersonIdentity(
                PersonType.EMPLOYEE,
                UUID.nameUUIDFromBytes(("intelbras:" + source).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    private AccessEventType eventType(IntelbrasAccessControlCardRecord record) {
        if (accessResult(record) != AccessResult.ALLOWED) {
            return AccessEventType.ACCESS_DENIED;
        }
        var type = lower(record.type());
        if (type.contains("exit") || type.contains("saida") || "2".equals(record.readerId())) {
            return AccessEventType.EXIT;
        }
        return AccessEventType.ENTRY;
    }

    private AccessResult accessResult(IntelbrasAccessControlCardRecord record) {
        var status = lower(record.status());
        var errorCode = record.errorCode();
        if ("1".equals(status) || "true".equals(status) || "success".equals(status) || "allowed".equals(status)) {
            return errorCode == null || errorCode.isBlank() || "0".equals(errorCode) ? AccessResult.ALLOWED : AccessResult.DENIED;
        }
        if ("0".equals(status) || "false".equals(status) || "denied".equals(status) || "fail".equals(status)) {
            return AccessResult.DENIED;
        }
        return errorCode == null || errorCode.isBlank() || "0".equals(errorCode) ? AccessResult.ALLOWED : AccessResult.ERROR;
    }

    private Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Device firmwares usually return local time as yyyy-MM-dd HH:mm:ss.
        }
        try {
            return LocalDateTime.parse(value, DEVICE_TIME).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String text(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
