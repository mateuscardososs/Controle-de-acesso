package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class IntelbrasDeviceConnectionService {

    private final DeviceRepository deviceRepository;
    private final IntelbrasProperties properties;

    public IntelbrasDeviceConnectionService(DeviceRepository deviceRepository, IntelbrasProperties properties) {
        this.deviceRepository = deviceRepository;
        this.properties = properties;
    }

    public List<IntelbrasDeviceConnection> allConfiguredDevices() {
        return deviceRepository.findAll().stream()
                .filter(this::looksLikeIntelbras)
                .map(this::connectionFor)
                .filter(IntelbrasDeviceConnection::configured)
                .toList();
    }

    public IntelbrasDeviceConnection connectionFor(UUID deviceId) {
        return deviceRepository.findById(deviceId)
                .map(this::connectionFor)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
    }

    public IntelbrasDeviceConnection connectionFor(Device device) {
        return new IntelbrasDeviceConnection(
                device,
                hostFor(device),
                firstNonBlank(device.getIntelbrasUsername(), properties.getDefaultUsername()),
                firstNonBlank(device.getIntelbrasPassword(), properties.getDefaultPassword())
        );
    }

    private boolean looksLikeIntelbras(Device device) {
        if (device.getIpAddress() == null || device.getIpAddress().isBlank()) {
            return false;
        }
        var model = device.getModel();
        return model == null || model.isBlank() || model.toLowerCase(java.util.Locale.ROOT).contains("intelbras");
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String hostFor(Device device) {
        var ipAddress = device.getIpAddress();
        var httpPort = device.getHttpPort();
        if (httpPort == null || httpPort == 80 || ipAddress == null || ipAddress.contains(":")) {
            return ipAddress;
        }
        return ipAddress + ":" + httpPort;
    }
}
