package br.com.sport.accesscontrol.devices;

import jakarta.validation.constraints.NotNull;

public record DeviceStatusRequest(
        @NotNull DeviceStatus status
) {
}
