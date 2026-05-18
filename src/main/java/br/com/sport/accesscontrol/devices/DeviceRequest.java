package br.com.sport.accesscontrol.devices;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DeviceRequest(
        @NotBlank String name,
        String model,
        String serialNumber,
        @NotBlank String ipAddress,
        String location,
        DeviceOperationType operationType,
        DeviceStatus status,
        @NotNull UUID areaId
) {
}
