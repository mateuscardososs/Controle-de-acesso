package br.com.sport.accesscontrol.audit;

import br.com.sport.accesscontrol.common.RequestContext;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final RequestContext requestContext;
    private final IntegrationEventPublisher publisher;

    public AuditService(AuditLogRepository auditLogRepository, RequestContext requestContext,
                        IntegrationEventPublisher publisher) {
        this.auditLogRepository = auditLogRepository;
        this.requestContext = requestContext;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(String action, String entityType, UUID entityId, Map<String, Object> details,
                           Map<String, Object> oldData, Map<String, Object> newData) {
        var enrichedDetails = new LinkedHashMap<String, Object>();
        if (details != null) {
            enrichedDetails.putAll(details);
        }
        var actorMetadata = requestContext.actorMetadata();
        if (actorMetadata != null) {
            enrichedDetails.putAll(actorMetadata);
        }
        var log = auditLogRepository.save(new AuditLog(
                requestContext.actorUserId().orElse(null),
                action,
                entityType,
                entityId,
                enrichedDetails,
                requestContext.actorIp(),
                oldData == null ? Map.of() : oldData,
                newData == null ? Map.of() : newData,
                requestContext.correlationId()
        ));
        publisher.publishAudit(Map.of("auditLogId", log.getId(), "action", log.getAction()));
        return log;
    }
}
