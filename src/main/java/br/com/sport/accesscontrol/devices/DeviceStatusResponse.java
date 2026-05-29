package br.com.sport.accesscontrol.devices;

import java.time.Instant;
import java.util.UUID;

public record DeviceStatusResponse(
        UUID deviceId,
        String deviceName,
        String controllerIp,
        DeviceStatus status,
        DeviceStatus onlineStatus,
        boolean online,
        Instant lastSeenAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastError,
        UUID areaId,
        String areaName,
        String location,
        boolean enabled,
        boolean active,
        int communicationFailures
) {
    public static DeviceStatusResponse from(Device device) {
        var online = device.getOnlineStatus() == DeviceStatus.ONLINE || device.getStatus() == DeviceStatus.ONLINE;
        var enabled = device.isActive() && device.getStatus() != DeviceStatus.MAINTENANCE;
        return new DeviceStatusResponse(
                device.getId(),
                device.getName(),
                device.getIpAddress(),
                device.getStatus(),
                device.getOnlineStatus(),
                online,
                device.getLastSeenAt(),
                device.getLastSuccessAt(),
                device.getLastFailureAt(),
                device.getLastError(),
                device.getArea().getId(),
                device.getArea().getName(),
                device.getLocation(),
                enabled,
                device.isActive(),
                device.getCommunicationFailures()
        );
    }
}
