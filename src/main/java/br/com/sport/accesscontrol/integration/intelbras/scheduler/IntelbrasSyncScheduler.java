package br.com.sport.accesscontrol.integration.intelbras.scheduler;

import br.com.sport.accesscontrol.devices.DeviceService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntelbrasSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncScheduler.class);
    private final DeviceService deviceService;
    private final IntelbrasIntegrationService integrationService;

    public IntelbrasSyncScheduler(DeviceService deviceService, IntelbrasIntegrationService integrationService) {
        this.deviceService = deviceService;
        this.integrationService = integrationService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void synchronizeOnlineDevices() {
        var onlineDevices = deviceService.findOnlineDevices();
        log.info("intelbras_sync_scheduler_tick online_devices={}", onlineDevices.size());
        onlineDevices.forEach(integrationService::synchronizeDevice);
    }
}
