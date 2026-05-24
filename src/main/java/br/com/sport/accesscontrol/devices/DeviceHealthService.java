package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import br.com.sport.accesscontrol.realtime.dto.SystemAlertMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class DeviceHealthService {

    private final DeviceService deviceService;
    private final AuditService auditService;
    private final RealtimePublisherService realtimePublisherService;
    private final AccessMetricsService accessMetricsService;

    public DeviceHealthService(DeviceService deviceService, AuditService auditService,
                               RealtimePublisherService realtimePublisherService,
                               AccessMetricsService accessMetricsService) {
        this.deviceService = deviceService;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
        this.accessMetricsService = accessMetricsService;
    }

    @Transactional
    public void heartbeat(UUID deviceId) {
        var device = deviceService.getById(deviceId);
        var oldStatus = device.getOnlineStatus();
        device.markHeartbeat();
        accessMetricsService.recordControllerOnline(device);
        auditService.record("DEVICE_HEARTBEAT", "Device", device.getId(),
                Map.of("deviceId", device.getId()), Map.of("onlineStatus", oldStatus), Map.of("onlineStatus", device.getOnlineStatus()));
        if (oldStatus != device.getOnlineStatus()) {
            realtimePublisherService.publishDeviceStatus(device, "Device heartbeat restored");
        }
    }

    @Transactional
    public void communicationFailure(UUID deviceId) {
        communicationFailure(deviceId, null);
    }

    @Transactional
    public void communicationFailure(UUID deviceId, String error) {
        var device = deviceService.getById(deviceId);
        var failures = device.getCommunicationFailures();
        var oldStatus = device.getStatus();
        device.registerCommunicationFailure(error);
        accessMetricsService.recordControllerCommunicationFailure(device);
        auditService.record("DEVICE_COMMUNICATION_FAILURE", "Device", device.getId(),
                Map.of("deviceId", device.getId()), Map.of("communicationFailures", failures),
                Map.of("communicationFailures", device.getCommunicationFailures()));
        if (oldStatus != device.getStatus()) {
            realtimePublisherService.publishDeviceStatus(device, "Device marked offline after communication failures");
        }
        if (failures < 3 && device.getCommunicationFailures() >= 3) {
            realtimePublisherService.publishSystemAlert(SystemAlertMessage.warning(
                    "Dispositivo offline",
                    "Dispositivo " + device.getName() + " atingiu o limite de falhas de comunicacao.",
                    "device-health"
            ));
        }
    }
}
