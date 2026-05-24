package br.com.sport.accesscontrol.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ManualAccessReleaseRequest(
        @NotBlank String personName,
        String personCpf,
        @NotNull UUID deviceId,
        @NotBlank String reason,
        String operatorObservation
) {
}
