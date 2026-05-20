package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntelbrasIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasIntegrationService.class);

    private final AccessControlProvider accessControlProvider;

    public IntelbrasIntegrationService(AccessControlProvider accessControlProvider) {
        this.accessControlProvider = accessControlProvider;
    }

    public void synchronizeDevice(Device device) {
        log.info("intelbras_sync_attempt device_id={} device_name={}", device.getId(), device.getName());
        accessControlProvider.fetchDeviceStatus(device.getId());
    }
}
