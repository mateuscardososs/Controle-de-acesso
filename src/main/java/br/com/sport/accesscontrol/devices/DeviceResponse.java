package br.com.sport.accesscontrol.devices;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID id,
        String name,
        String model,
        String serialNumber,
        String ipAddress,
        Integer httpPort,
        String intelbrasUsername,
        boolean intelbrasPasswordConfigured,
        String location,
        DeviceOperationType operationType,
        DeviceStatus status,
        UUID areaId,
        String areaName,
        Instant lastSeenAt,
        Instant lastHeartbeatAt,
        int communicationFailures,
        DeviceStatus onlineStatus,
        Instant createdAt,
        Instant updatedAt
) {
    static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getId(),
                device.getName(),
                device.getModel(),
                device.getSerialNumber(),
                device.getIpAddress(),
                device.getHttpPort(),
                device.getIntelbrasUsername(),
                device.getIntelbrasPassword() != null && !device.getIntelbrasPassword().isBlank(),
                device.getLocation(),
                device.getOperationType(),
                device.getStatus(),
                device.getArea().getId(),
                device.getArea().getName(),
                device.getLastSeenAt(),
                device.getLastHeartbeatAt(),
                device.getCommunicationFailures(),
                device.getOnlineStatus(),
                device.getCreatedAt(),
                device.getUpdatedAt()
        );
    }
}
