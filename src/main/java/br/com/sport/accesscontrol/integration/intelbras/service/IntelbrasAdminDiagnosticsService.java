package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class IntelbrasAdminDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasAdminDiagnosticsService.class);

    private final DeviceRepository deviceRepository;
    private final IntelbrasProperties properties;

    public IntelbrasAdminDiagnosticsService(DeviceRepository deviceRepository,
                                            IntelbrasProperties properties) {
        this.deviceRepository = deviceRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<IntelbrasDeviceDiagnosticResponse> diagnoseAll() {
        return deviceRepository.findAll().stream()
                .filter(Device::isActive)
                .map(this::diagnose)
                .toList();
    }

    private IntelbrasDeviceDiagnosticResponse diagnose(Device device) {
        var id = device.getId();
        var areaId = device.getArea() == null ? null : device.getArea().getId();
        var areaName = device.getArea() == null ? null : device.getArea().getName();
        var hasCredentials = hasText(device.getIntelbrasUsername()) && hasText(device.getIntelbrasPassword());
        var hasPassword = hasText(device.getIntelbrasPassword());
        var hasUsername = hasText(firstNonBlank(device.getIntelbrasUsername(), properties.getDefaultUsername()));
        var hasIp = hasText(device.getIpAddress());
        var hasArea = device.getArea() != null && device.getArea().getId() != null;
        var looksLikeIntelbras = looksLikeIntelbras(device);
        var isOnline = device.getStatus() == DeviceStatus.ONLINE || device.getOnlineStatus() == DeviceStatus.ONLINE;

        var ineligibilityReason = computeIneligibilityReason(device, hasIp, looksLikeIntelbras, isOnline, hasArea, hasUsername, hasPassword);
        var eligibleForSync = ineligibilityReason == null;

        log.info("intelbras_device_diagnostic id={} name={} ip={} area={} online={} credentials={} eligible={} reason={}",
                id, device.getName(), device.getIpAddress(), areaName, isOnline, hasCredentials, eligibleForSync,
                ineligibilityReason == null ? "ok" : ineligibilityReason);

        return new IntelbrasDeviceDiagnosticResponse(
                id,
                device.getName(),
                device.getModel(),
                areaId,
                areaName,
                device.getIpAddress(),
                device.getHttpPort(),
                device.isActive(),
                device.getStatus(),
                device.getOnlineStatus(),
                hasCredentials,
                hasPassword,
                eligibleForSync,
                ineligibilityReason,
                device.getLastSuccessAt(),
                device.getLastFailureAt(),
                device.getLastError(),
                device.getCommunicationFailures()
        );
    }

    private String computeIneligibilityReason(Device device, boolean hasIp, boolean looksLikeIntelbras,
                                               boolean isOnline, boolean hasArea, boolean hasUsername, boolean hasPassword) {
        if (!device.isActive()) return "Dispositivo inativo";
        if (!hasIp) return "Endereço IP não configurado";
        if (!looksLikeIntelbras) return "Modelo/nome não identificado como Intelbras SS55xx e sem senha configurada";
        if (!isOnline) return "Dispositivo OFFLINE — verificar conectividade de rede";
        if (!hasArea) return "Área não configurada";
        if (!hasUsername) return "Usuário Intelbras não configurado";
        if (!hasPassword) {
            return "Senha Intelbras ausente — pode ter sido apagada ao editar o dispositivo. "
                    + "Acesse 'Editar dispositivo' e informe a senha novamente.";
        }
        return null;
    }

    private boolean looksLikeIntelbras(Device device) {
        if (hasText(device.getIntelbrasPassword())) {
            return true;
        }
        var value = normalize(device.getModel() + " " + device.getName());
        return value.contains("intelbras")
                || value.contains("ss 5531")
                || value.contains("ss5531")
                || value.contains("ss 5541")
                || value.contains("ss5541");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public record IntelbrasDeviceDiagnosticResponse(
            UUID id,
            String name,
            String model,
            UUID areaId,
            String areaName,
            String ipAddress,
            Integer httpPort,
            boolean active,
            DeviceStatus status,
            DeviceStatus onlineStatus,
            boolean hasCredentials,
            boolean hasPassword,
            boolean eligibleForSync,
            String ineligibilityReason,
            java.time.Instant lastSuccessAt,
            java.time.Instant lastFailureAt,
            String lastError,
            int communicationFailures
    ) {}
}
