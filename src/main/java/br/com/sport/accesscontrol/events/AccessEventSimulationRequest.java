package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.common.PersonType;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AccessEventSimulationRequest(
        @NotNull PersonType personType,
        @NotNull UUID personId,
        @NotNull UUID deviceId,
        @NotNull AccessEventType eventType,
        @NotNull AccessResult accessResult,
        Instant eventTime,
        String origin,
        Map<String, Object> rawPayload
) {
}
