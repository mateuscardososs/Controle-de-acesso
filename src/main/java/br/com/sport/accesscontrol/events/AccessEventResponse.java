package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AccessEventResponse(
        UUID id,
        PersonType personType,
        UUID personId,
        String personName,
        String personCpf,
        String externalUserId,
        String rawCardName,
        UUID deviceId,
        UUID areaId,
        AccessEventType eventType,
        AccessResult accessResult,
        Instant eventTime,
        String origin,
        Map<String, Object> rawPayload,
        Instant createdAt
) {
    static AccessEventResponse from(AccessEvent accessEvent) {
        return new AccessEventResponse(
                accessEvent.getId(),
                accessEvent.getPersonType(),
                accessEvent.getPersonId(),
                accessEvent.getPersonName(),
                accessEvent.getPersonCpf(),
                accessEvent.getExternalUserId(),
                accessEvent.getRawCardName(),
                accessEvent.getDevice().getId(),
                accessEvent.getArea().getId(),
                accessEvent.getEventType(),
                accessEvent.getAccessResult(),
                accessEvent.getEventTime(),
                accessEvent.getOrigin(),
                accessEvent.getRawPayload(),
                accessEvent.getCreatedAt()
        );
    }
}
