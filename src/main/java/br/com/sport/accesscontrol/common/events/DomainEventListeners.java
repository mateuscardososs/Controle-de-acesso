package br.com.sport.accesscontrol.common.events;

import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.sync.EmployeeReadyForSyncEvent;
import br.com.sport.accesscontrol.integration.sync.GuestReadyForSyncEvent;
import br.com.sport.accesscontrol.integration.sync.IntelbrasSyncMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DomainEventListeners {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListeners.class);
    private final IntegrationEventPublisher publisher;

    public DomainEventListeners(IntegrationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    void on(EmployeeCreatedEvent event) {
        log.info("employee_created_event employee_id={}", event.employeeId());
        publisher.publishEmployeeSync(event);
    }

    @EventListener
    void on(EmployeeUpdatedEvent event) {
        log.info("employee_updated_event employee_id={}", event.employeeId());
        publisher.publishEmployeeSync(event);
    }

    @EventListener
    void on(EmployeeDeactivatedEvent event) {
        log.info("employee_deactivated_event employee_id={}", event.employeeId());
        publisher.publishEmployeeSync(event);
    }

    @EventListener
    void on(AccessPermissionChangedEvent event) {
        log.info("access_permission_changed_event permission_id={}", event.permissionId());
        publisher.publishEmployeeSync(event);
    }

    @EventListener
    void on(DeviceStatusChangedEvent event) {
        log.info("device_status_changed_event device_id={} status={}", event.deviceId(), event.status());
    }

    @EventListener
    void on(AccessEventReceivedEvent event) {
        log.info("access_event_received_event access_event_id={}", event.accessEventId());
        publisher.publishAccessEvent(event);
    }

    @EventListener
    void on(EmployeeReadyForSyncEvent event) {
        log.info("employee_ready_for_sync_event employee_id={}", event.employeeId());
        publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.EMPLOYEE, event.employeeId(), 1));
    }

    @EventListener
    void on(GuestReadyForSyncEvent event) {
        log.info("guest_ready_for_sync_event guest_id={}", event.guestId());
        publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.GUEST, event.guestId(), 1));
    }
}
