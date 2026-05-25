package br.com.sport.accesscontrol.integration.intelbras.scheduler;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasEventImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntelbrasEventPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasEventPollingScheduler.class);

    private final IntelbrasProperties properties;
    private final IntelbrasEventImportService importService;

    public IntelbrasEventPollingScheduler(IntelbrasProperties properties, IntelbrasEventImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Scheduled(fixedDelayString = "#{@intelbrasProperties.eventsPollingInterval.toMillis()}")
    public void pollAccessControlEvents() {
        if (!properties.isEventsPollingEnabled()) {
            return;
        }
        try {
            importService.importOnlineAccessControlEvents();
        } catch (Exception exception) {
            log.warn("event_polling_failed reason={}", exception.getMessage());
        }
    }
}
