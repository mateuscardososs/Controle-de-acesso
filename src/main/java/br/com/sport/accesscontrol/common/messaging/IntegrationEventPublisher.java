package br.com.sport.accesscontrol.common.messaging;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.sync.IntelbrasSyncMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class IntegrationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IntegrationEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final GuestRepository guestRepository;
    private final EmployeeRepository employeeRepository;

    public IntegrationEventPublisher(RabbitTemplate rabbitTemplate,
                                     GuestRepository guestRepository,
                                     EmployeeRepository employeeRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.guestRepository = guestRepository;
        this.employeeRepository = employeeRepository;
    }

    public void publishEmployeeSync(Object event) {
        publish(RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE, "employee.sync", event);
    }

    public void publishIntelbrasSync(IntelbrasSyncMessage event) {
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE, "intelbras.sync.requested", event);
            log.info("rabbit_intelbras_sync_published person_type={} person_id={} attempt={}",
                    event.personType(), event.personId(), event.attempt());
            if (event.personType() == PersonType.GUEST) {
                log.info("GUEST_SYNC_EVENT_PUBLISHED person_type=GUEST person_id={} attempt={}",
                        event.personId(), event.attempt());
            }
        } catch (AmqpException exception) {
            var errorMessage = "Falha ao publicar na fila: " + exception.getMessage();
            log.warn("rabbit_event_publish_skipped exchange={} routing_key={} person_type={} person_id={} reason={}",
                    RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE, "intelbras.sync.requested",
                    event.personType(), event.personId(), exception.getMessage());
            log.error("SYNC_PUBLISH_FAILED person_type={} person_id={} error_message={}",
                    event.personType(), event.personId(), exception.getMessage());
            markSyncFailedInDatabase(event, errorMessage);
        }
    }

    public void publishAccessEvent(Object event) {
        publish(RabbitMqConfig.ACCESS_EVENTS_EXCHANGE, "access.event.received", event);
    }

    public void publishAudit(Object event) {
        publish(RabbitMqConfig.AUDIT_EVENTS_EXCHANGE, "audit.created", event);
    }

    private void publish(String exchange, String routingKey, Object event) {
        var envelope = Map.of("event", event, "publishedAt", Instant.now().toString());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, envelope);
            log.info("rabbit_event_published exchange={} routing_key={}", exchange, routingKey);
        } catch (AmqpException exception) {
            log.warn("rabbit_event_publish_skipped exchange={} routing_key={} reason={}",
                    exchange, routingKey, exception.getMessage());
        }
    }

    /**
     * When RabbitMQ publish fails, mark the entity as SYNC_FAILED immediately so the record
     * does not stay in PENDING_SYNC forever. Each repository call creates its own short transaction
     * (no surrounding transaction exists here — this runs after AFTER_COMMIT).
     */
    private void markSyncFailedInDatabase(IntelbrasSyncMessage event, String errorMessage) {
        try {
            if (event.personType() == PersonType.GUEST) {
                guestRepository.findById(event.personId()).ifPresent(guest -> {
                    guest.markSyncFailed(errorMessage);
                    guestRepository.save(guest);
                    log.info("SYNC_PUBLISH_FAILED person_type=GUEST person_id={} sync_status_updated=SYNC_FAILED",
                            event.personId());
                });
            } else if (event.personType() == PersonType.EMPLOYEE) {
                employeeRepository.findById(event.personId()).ifPresent(employee -> {
                    employee.markSyncFailed(errorMessage);
                    employeeRepository.save(employee);
                    log.info("SYNC_PUBLISH_FAILED person_type=EMPLOYEE person_id={} sync_status_updated=SYNC_FAILED",
                            event.personId());
                });
            }
        } catch (Exception dbException) {
            // Best-effort: if the DB update also fails, log it but do not propagate —
            // the caller (controller) already returned 200 with PENDING_SYNC.
            log.error("SYNC_PUBLISH_FAILED_DB_UPDATE_ALSO_FAILED person_type={} person_id={} db_error={}",
                    event.personType(), event.personId(), dbException.getMessage());
        }
    }
}
