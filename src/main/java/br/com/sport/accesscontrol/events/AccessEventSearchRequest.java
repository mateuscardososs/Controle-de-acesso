package br.com.sport.accesscontrol.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AccessEventSearchRequest(
        int page,
        int size,
        Instant startDate,
        Instant endDate,
        String personName,
        String personCpf,
        LocalDate invitedDay,
        String invitedLounge,
        UUID deviceId,
        UUID areaId,
        AccessEventType eventType,
        AccessResult accessResult,
        RecognitionStatus recognitionStatus,
        PassageStatus passageStatus,
        ReleaseMethod releaseMethod,
        String origin,
        Boolean manualOnly
) {
    public AccessEventSearchRequest(
            int page,
            int size,
            Instant startDate,
            Instant endDate,
            String personName,
            String personCpf,
            UUID deviceId,
            UUID areaId,
            AccessEventType eventType,
            AccessResult accessResult,
            RecognitionStatus recognitionStatus,
            PassageStatus passageStatus,
            ReleaseMethod releaseMethod,
            String origin,
            Boolean manualOnly
    ) {
        this(page, size, startDate, endDate, personName, personCpf, null, null, deviceId, areaId,
                eventType, accessResult, recognitionStatus, passageStatus, releaseMethod, origin, manualOnly);
    }
}
