package br.com.sport.accesscontrol.integration.intelbras.simulator;

import br.com.sport.accesscontrol.events.AccessEventType;
import br.com.sport.accesscontrol.events.AccessResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record IntelbrasAccessEventSimulatorRequest(
        @NotBlank String cpf,
        @NotNull UUID deviceId,
        @NotNull AccessEventType eventType,
        @NotNull AccessResult result
) {
}
