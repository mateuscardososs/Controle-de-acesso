package br.com.sport.accesscontrol.realtime.dto;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.events.AccessEventType;
import br.com.sport.accesscontrol.events.AccessResult;

import java.time.Instant;
import java.util.UUID;

public record RealtimeAccessEventMessage(
        UUID id,
        PersonType personType,
        UUID personId,
        String personName,
        String personCpf,
        String externalUserId,
        String rawCardName,
        UUID deviceId,
        String deviceName,
        UUID areaId,
        String areaName,
        AccessEventType eventType,
        AccessResult accessResult,
        Instant eventTime,
        String origin,
        String source,
        Instant createdAt
) {
}
