package br.com.sport.accesscontrol.common.events;

import br.com.sport.accesscontrol.devices.DeviceStatus;

import java.util.UUID;

public record DeviceStatusChangedEvent(UUID deviceId, DeviceStatus status) {
}
