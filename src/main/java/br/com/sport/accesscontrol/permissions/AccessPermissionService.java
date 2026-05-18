package br.com.sport.accesscontrol.permissions;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.events.AccessPermissionChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AccessPermissionService {

    private final AccessPermissionRepository repository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AccessPermissionService(AccessPermissionRepository repository, AuditService auditService,
                                   ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AccessPermission saveWithAudit(AccessPermission permission) {
        var saved = repository.save(permission);
        auditService.record("ACCESS_PERMISSION_CHANGED", "AccessPermission", saved.getId(),
                Map.of("permissionId", saved.getId()), Map.of(), Map.of("permissionId", saved.getId()));
        eventPublisher.publishEvent(new AccessPermissionChangedEvent(saved.getId()));
        return saved;
    }
}
