package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class IntelbrasDeviceConnectionService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasDeviceConnectionService.class);

    private final DeviceRepository deviceRepository;
    private final IntelbrasProperties properties;

    public IntelbrasDeviceConnectionService(DeviceRepository deviceRepository, IntelbrasProperties properties) {
        this.deviceRepository = deviceRepository;
        this.properties = properties;
    }

    public List<IntelbrasDeviceConnection> allConfiguredDevices() {
        return configuredCandidates(false);
    }

    public List<IntelbrasDeviceConnection> onlineConfiguredDevices() {
        return configuredCandidates(true);
    }

    public Optional<IntelbrasDeviceConnection> selectOnlineConfiguredDevice(UUID preferredAreaId) {
        var candidates = onlineConfiguredDevices();
        Optional<IntelbrasDeviceConnection> selected = Optional.empty();
        if (preferredAreaId != null) {
            selected = candidates.stream()
                    .filter(connection -> sameArea(connection.device(), preferredAreaId))
                    .findFirst();
        }
        if (selected.isEmpty()) {
            selected = candidates.stream().findFirst();
        }
        selected.ifPresentOrElse(
                connection -> log.info("intelbras_selected_device device_id={} area_id={} preferred_area_id={} host={} model={} status={} online_status={}",
                        connection.device().getId(), areaId(connection.device()), preferredAreaId,
                        maskHost(connection.host()), connection.device().getModel(),
                        connection.device().getStatus(), connection.device().getOnlineStatus()),
                () -> log.warn("intelbras_selected_device device_id=none preferred_area_id={} reason=no_eligible_online_configured_device",
                        preferredAreaId)
        );
        return selected;
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
                trimToNull(device.getIntelbrasPassword())
        );
    }

    private List<IntelbrasDeviceConnection> configuredCandidates(boolean requireOnline) {
        var devices = deviceRepository.findAll();
        log.info("intelbras_device_candidates_count total={} require_online={}", devices.size(), requireOnline);
        return devices.stream()
                .filter(device -> eligible(device, requireOnline))
                .map(this::connectionFor)
                .toList();
    }

    private boolean eligible(Device device, boolean requireOnline) {
        if (!hasText(device.getIpAddress())) {
            reject(device, "missing_ip_address");
            return false;
        }
        if (!looksLikeIntelbras(device)) {
            reject(device, "model_not_intelbras_ss55xx");
            return false;
        }
        if (requireOnline && !online(device)) {
            reject(device, "not_online");
            return false;
        }
        if (device.getArea() == null || device.getArea().getId() == null) {
            reject(device, "missing_area");
            return false;
        }
        if (!hasText(firstNonBlank(device.getIntelbrasUsername(), properties.getDefaultUsername()))) {
            reject(device, "missing_username");
            return false;
        }
        if (!hasText(device.getIntelbrasPassword())) {
            reject(device, "missing_device_password");
            return false;
        }
        var connection = connectionFor(device);
        if (!connection.configured()) {
            reject(device, "missing_credentials");
            return false;
        }
        return true;
    }

    private boolean looksLikeIntelbras(Device device) {
        var value = normalize(device.getModel() + " " + device.getName());
        return value.contains("intelbras")
                || value.contains("ss 5531")
                || value.contains("ss5531")
                || value.contains("ss 5541")
                || value.contains("ss5541");
    }

    private boolean online(Device device) {
        return device.getStatus() == DeviceStatus.ONLINE || device.getOnlineStatus() == DeviceStatus.ONLINE;
    }

    private boolean sameArea(Device device, UUID preferredAreaId) {
        return preferredAreaId != null && preferredAreaId.equals(areaId(device));
    }

    private UUID areaId(Device device) {
        return device.getArea() == null ? null : device.getArea().getId();
    }

    private void reject(Device device, String reason) {
        log.info("intelbras_device_candidate_rejected reason={} device_id={} name={} model={} status={} online_status={} ip_configured={} credentials_configured={} area_id={}",
                reason,
                device == null ? null : device.getId(),
                device == null ? null : device.getName(),
                device == null ? null : device.getModel(),
                device == null ? null : device.getStatus(),
                device == null ? null : device.getOnlineStatus(),
                device != null && hasText(device.getIpAddress()),
                device != null && connectionFor(device).configured(),
                device == null ? null : areaId(device));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String hostFor(Device device) {
        var ipAddress = device.getIpAddress();
        var httpPort = device.getHttpPort();
        if (httpPort == null || httpPort == 80 || ipAddress == null || ipAddress.contains(":")) {
            return ipAddress;
        }
        return ipAddress + ":" + httpPort;
    }

    private String maskHost(String host) {
        if (host == null || host.isBlank()) {
            return "<empty-host>";
        }
        return host.replaceAll("(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}", "$1.*.*");
    }
}
