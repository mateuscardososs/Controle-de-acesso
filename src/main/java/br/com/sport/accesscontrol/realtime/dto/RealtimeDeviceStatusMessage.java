package br.com.sport.accesscontrol.realtime.dto;

import br.com.sport.accesscontrol.devices.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

public record RealtimeDeviceStatusMessage(
        UUID deviceId,
        String deviceName,
        DeviceStatus status,
        Instant lastSeenAt,
        Instant lastHeartbeatAt,
        int communicationFailures,
        String message
) {
}
