package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceHealthService;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntelbrasIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasIntegrationService.class);

    private final AccessControlProvider accessControlProvider;
    private final DeviceHealthService deviceHealthService;

    public IntelbrasIntegrationService(AccessControlProvider accessControlProvider, DeviceHealthService deviceHealthService) {
        this.accessControlProvider = accessControlProvider;
        this.deviceHealthService = deviceHealthService;
    }

    public void synchronizeDevice(Device device) {
        log.info("intelbras_sync_attempt device_id={} device_name={}", device.getId(), device.getName());
        try {
            var status = accessControlProvider.fetchDeviceStatus(device.getId());
            if (status.status() == DeviceStatus.ONLINE) {
                deviceHealthService.heartbeat(device.getId());
            } else {
                deviceHealthService.communicationFailure(device.getId(), status.details());
            }
        } catch (Exception exception) {
            log.warn("intelbras_sync_attempt_failed device_id={} device_name={} error={}",
                    device.getId(), device.getName(), exception.getMessage());
            deviceHealthService.communicationFailure(device.getId(), exception.getMessage());
        }
    }
}
