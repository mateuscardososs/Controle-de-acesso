package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

public record ProviderDeviceStatus(
        UUID deviceId,
        DeviceStatus status,
        Instant observedAt,
        String details
) {
}
