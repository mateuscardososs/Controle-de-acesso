package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record AccessEventResponse(
        UUID id,
        PersonType personType,
        UUID personId,
        String personName,
        String personCpf,
        String personEmail,
        String personPhone,
        LocalDate invitedDay,
        String invitedLounge,
        String externalUserId,
        String rawCardName,
        UUID deviceId,
        String deviceName,
        UUID areaId,
        String areaName,
        AccessEventType eventType,
        AccessResult accessResult,
        EventCategory eventCategory,
        RecognitionStatus recognitionStatus,
        PassageStatus passageStatus,
        ReleaseMethod releaseMethod,
        UUID operatorUserId,
        String manualReason,
        String controllerMethod,
        String controllerDoor,
        String controllerReaderId,
        String controllerRecNo,
        String decisionReason,
        Instant eventTime,
        Instant occurredAt,
        String origin,
        Map<String, Object> rawPayload,
        Instant createdAt,
        boolean cooldownBlocked,
        String cooldownReason
) {
    public static AccessEventResponse from(AccessEvent accessEvent) {
        var device = accessEvent.getDevice();
        var area = accessEvent.getArea();
        return new AccessEventResponse(
                accessEvent.getId(),
                accessEvent.getPersonType(),
                accessEvent.getPersonId(),
                accessEvent.getPersonName(),
                accessEvent.getPersonCpf(),
                accessEvent.getPersonEmail(),
                accessEvent.getPersonPhone(),
                accessEvent.getInvitedDay(),
                accessEvent.getInvitedLounge(),
                accessEvent.getExternalUserId(),
                accessEvent.getRawCardName(),
                device != null ? device.getId() : null,
                device != null ? device.getName() : null,
                area != null ? area.getId() : null,
                area != null ? area.getName() : null,
                accessEvent.getEventType(),
                accessEvent.getAccessResult(),
                accessEvent.getEventCategory(),
                accessEvent.getRecognitionStatus(),
                accessEvent.getPassageStatus(),
                accessEvent.getReleaseMethod(),
                accessEvent.getOperatorUserId(),
                accessEvent.getManualReason(),
                accessEvent.getControllerMethod(),
                accessEvent.getControllerDoor(),
                accessEvent.getControllerReaderId(),
                accessEvent.getControllerRecNo(),
                accessEvent.getDecisionReason(),
                accessEvent.getEventTime(),
                accessEvent.getOccurredAt(),
                accessEvent.getOrigin(),
                accessEvent.getRawPayload(),
                accessEvent.getCreatedAt(),
                accessEvent.isCooldownBlocked(),
                accessEvent.getCooldownReason()
        );
    }
}
