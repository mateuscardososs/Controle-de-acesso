package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.events.AccessEventType;
import br.com.sport.accesscontrol.events.AccessResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NormalizedAccessEvent(
        PersonType personType,
        UUID personId,
        UUID deviceId,
        AccessEventType eventType,
        AccessResult accessResult,
        Instant eventTime,
        String origin,
        String personName,
        String personCpf,
        String externalUserId,
        String rawCardName,
        Map<String, Object> rawPayload
) {
    public NormalizedAccessEvent(
            PersonType personType,
            UUID personId,
            UUID deviceId,
            AccessEventType eventType,
            AccessResult accessResult,
            Instant eventTime,
            String origin,
            Map<String, Object> rawPayload
    ) {
        this(personType, personId, deviceId, eventType, accessResult, eventTime, origin, null, null, null, null, rawPayload);
    }
}
