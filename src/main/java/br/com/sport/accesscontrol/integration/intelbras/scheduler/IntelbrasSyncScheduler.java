package br.com.sport.accesscontrol.integration.intelbras.scheduler;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntelbrasSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncScheduler.class);
    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasIntegrationService integrationService;
    private final IntelbrasProperties properties;

    public IntelbrasSyncScheduler(IntelbrasDeviceConnectionService connectionService,
                                  IntelbrasIntegrationService integrationService,
                                  IntelbrasProperties properties) {
        this.connectionService = connectionService;
        this.integrationService = integrationService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{@intelbrasProperties.healthInterval.toMillis()}")
    public void synchronizeConfiguredDevices() {
        if (properties.getMode() != IntelbrasProperties.Mode.REAL) {
            log.debug("device_health_check_skipped reason=mode_not_real mode={}", properties.getMode());
            return;
        }
        var configured = connectionService.allConfiguredDevices();
        log.info("intelbras_sync_scheduler_tick configured_devices={} interval_ms={}",
                configured.size(), properties.getHealthInterval().toMillis());
        configured.forEach(connection -> {
            try {
                integrationService.synchronizeDevice(connection.device());
            } catch (Exception exception) {
                log.warn("intelbras_sync_scheduler_device_failed device_id={} device_name={} error={}",
                        connection.device().getId(), connection.device().getName(), exception.getMessage());
            }
        });
    }
}
