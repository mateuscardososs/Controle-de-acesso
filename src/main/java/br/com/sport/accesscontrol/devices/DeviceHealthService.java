package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class DeviceHealthService {

    private final DeviceService deviceService;
    private final AuditService auditService;

    public DeviceHealthService(DeviceService deviceService, AuditService auditService) {
        this.deviceService = deviceService;
        this.auditService = auditService;
    }

    @Transactional
    public void heartbeat(UUID deviceId) {
        var device = deviceService.getById(deviceId);
        var oldStatus = device.getOnlineStatus();
        device.markHeartbeat();
        auditService.record("DEVICE_HEARTBEAT", "Device", device.getId(),
                Map.of("deviceId", device.getId()), Map.of("onlineStatus", oldStatus), Map.of("onlineStatus", device.getOnlineStatus()));
    }

    @Transactional
    public void communicationFailure(UUID deviceId) {
        var device = deviceService.getById(deviceId);
        var failures = device.getCommunicationFailures();
        device.registerCommunicationFailure();
        auditService.record("DEVICE_COMMUNICATION_FAILURE", "Device", device.getId(),
                Map.of("deviceId", device.getId()), Map.of("communicationFailures", failures),
                Map.of("communicationFailures", device.getCommunicationFailures()));
    }
}
