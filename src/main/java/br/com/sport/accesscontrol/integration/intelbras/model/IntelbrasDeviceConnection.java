package br.com.sport.accesscontrol.integration.intelbras.model;

import br.com.sport.accesscontrol.devices.Device;

public record IntelbrasDeviceConnection(
        Device device,
        String host,
        String username,
        String password
) {
    public boolean configured() {
        return device != null
                && host != null && !host.isBlank()
                && username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
