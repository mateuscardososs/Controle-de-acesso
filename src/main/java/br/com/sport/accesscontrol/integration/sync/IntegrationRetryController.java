package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/integration/retry")
public class IntegrationRetryController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntegrationRetryController.class);

    private final IntegrationEventPublisher publisher;
    private final AuditService auditService;

    public IntegrationRetryController(IntegrationEventPublisher publisher, AuditService auditService) {
        this.publisher = publisher;
        this.auditService = auditService;
    }

    @PostMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> retry(@PathVariable String type, @PathVariable UUID id) {
        var personType = PersonType.valueOf(type.toUpperCase());
        log.info("intelbras_sync_manual_retry_requested person_type={} person_id={} attempt=1", personType, id);
        auditService.record("INTELBRAS_SYNC_MANUAL_RETRY", personType.name(), id, Map.of(), Map.of(), Map.of());
        publisher.publishIntelbrasSync(new IntelbrasSyncMessage(personType, id, 1));
        return Map.of("status", "queued", "type", personType.name(), "id", id.toString());
    }
}
