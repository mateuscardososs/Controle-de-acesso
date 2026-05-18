package br.com.sport.accesscontrol.audit;

import br.com.sport.accesscontrol.common.RequestContext;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        var log = auditLogRepository.save(new AuditLog(
                null,
                action,
                entityType,
                entityId,
                details,
                requestContext.actorIp(),
                oldData,
                newData,
                requestContext.correlationId()
        ));
        publisher.publishAudit(Map.of("auditLogId", log.getId(), "action", log.getAction()));
        return log;
    }
}
