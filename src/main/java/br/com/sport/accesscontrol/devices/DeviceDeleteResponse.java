package br.com.sport.accesscontrol.devices;

public record DeviceDeleteResponse(
        boolean removed,
        boolean deactivated,
        String message,
        DeviceResponse device
) {
}
