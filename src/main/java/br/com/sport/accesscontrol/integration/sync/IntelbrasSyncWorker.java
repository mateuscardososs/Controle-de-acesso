package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.Guest;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import br.com.sport.accesscontrol.realtime.dto.SystemAlertMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class IntelbrasSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncWorker.class);

    private final EmployeeRepository employeeRepository;
    private final GuestRepository guestRepository;
    private final AccessControlProvider provider;
    private final AuditService auditService;
    private final IntegrationEventPublisher eventPublisher;
    private final IntegrationSyncRealtimePublisher realtimePublisher;
    private final RealtimePublisherService systemRealtimePublisher;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;

    public IntelbrasSyncWorker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                               AccessControlProvider provider, AuditService auditService,
                               IntegrationEventPublisher eventPublisher,
                               IntegrationSyncRealtimePublisher realtimePublisher,
                               RealtimePublisherService systemRealtimePublisher,
                               MeterRegistry meterRegistry,
                               @Value("${app.integration.intelbras.sync.max-attempts:3}") int maxAttempts) {
        this.employeeRepository = employeeRepository;
        this.guestRepository = guestRepository;
        this.provider = provider;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.systemRealtimePublisher = systemRealtimePublisher;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = RabbitMqConfig.INTELBRAS_SYNC_QUEUE)
    public void consume(IntelbrasSyncMessage message) {
        process(message);
    }

    @Transactional
    public void process(IntelbrasSyncMessage message) {
        log.info("intelbras_sync_worker_start person_type={} person_id={} attempt={}",
                message.personType(), message.personId(), message.attempt());
        var sample = Timer.start(meterRegistry);
        try {
            ProviderSyncResult result = switch (message.personType()) {
                case EMPLOYEE -> syncEmployee(message.personId());
                case GUEST -> syncGuest(message.personId());
            };
            sample.stop(meterRegistry.timer("intelbras.sync.latency", "result", result.status().name()));
            if (result.successful()) {
                meterRegistry.counter("intelbras.sync.success.count", "type", message.personType().name()).increment();
                return;
            }
            handleFailure(message, result.message());
        } catch (Exception exception) {
            sample.stop(meterRegistry.timer("intelbras.sync.latency", "result", "EXCEPTION"));
            handleFailure(message, exception.getMessage());
        }
    }

    private ProviderSyncResult syncEmployee(UUID employeeId) {
        var employee = employeeRepository.findById(employeeId).orElseThrow();
        employee.markSyncing();
        auditStart(PersonType.EMPLOYEE, employeeId, employee.getSyncAttempts());
        realtimePublisher.publish(PersonType.EMPLOYEE, employeeId, SyncStatus.SYNCING, "Sincronizando Intelbras");
        var result = provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE,
                employee.getId(),
                employee.getCpf(),
                employee.getFullName(),
                employee.getFacePhotoUrl(),
                employee.getStatus() == br.com.sport.accesscontrol.employees.EmployeeStatus.ACTIVE,
                employee.getAccessValidFrom(),
                employee.getAccessValidUntil()
        ));
        finishEmployee(employee, result);
        return result;
    }

    private ProviderSyncResult syncGuest(UUID guestId) {
        var guest = guestRepository.findById(guestId).orElseThrow();
        guest.markSyncing();
        auditStart(PersonType.GUEST, guestId, guest.getSyncAttempts());
        realtimePublisher.publish(PersonType.GUEST, guestId, SyncStatus.SYNCING, "Sincronizando Intelbras");
        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST,
                guest.getId(),
                guest.getCpf(),
                guest.getFullName(),
                guest.getFacePhotoUrl(),
                guest.getStatus() == br.com.sport.accesscontrol.guests.GuestStatus.COMPLETED,
                guest.getVisitStart(),
                guest.getVisitEnd()
        ));
        finishGuest(guest, result);
        return result;
    }

    private void finishEmployee(Employee employee, ProviderSyncResult result) {
        if (result.status() == ProviderSyncStatus.SUCCESS) {
            employee.markSynced();
            auditSuccess(PersonType.EMPLOYEE, employee.getId(), result);
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNCED, result.message());
        } else {
            employee.markSyncFailed(result.message());
            auditFailure(PersonType.EMPLOYEE, employee.getId(), result.message());
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNC_FAILED, result.message());
        }
    }

    private void finishGuest(Guest guest, ProviderSyncResult result) {
        if (result.status() == ProviderSyncStatus.SUCCESS) {
            guest.markSynced();
            auditSuccess(PersonType.GUEST, guest.getId(), result);
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNCED, result.message());
        } else {
            guest.markSyncFailed(result.message());
            auditFailure(PersonType.GUEST, guest.getId(), result.message());
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNC_FAILED, result.message());
        }
    }

    private void handleFailure(IntelbrasSyncMessage message, String error) {
        meterRegistry.counter("intelbras.sync.failed.count", "type", message.personType().name()).increment();
        if (message.attempt() < maxAttempts) {
            meterRegistry.counter("intelbras.sync.retry.count", "type", message.personType().name()).increment();
            auditService.record("INTELBRAS_SYNC_RETRY", message.personType().name(), message.personId(),
                    Map.of("attempt", message.attempt(), "error", safe(error)), Map.of(), Map.of());
            eventPublisher.publishIntelbrasSync(message.nextAttempt());
            return;
        }
        systemRealtimePublisher.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.ERROR,
                "Falha de sincronização Intelbras",
                message.personType() + " " + message.personId() + " excedeu tentativas de sync.",
                "intelbras-sync",
                Instant.now()
        ));
        throw new AmqpRejectAndDontRequeueException("Intelbras sync failed after retries: " + safe(error));
    }

    private void auditStart(PersonType type, UUID id, int attempt) {
        auditService.record("INTELBRAS_SYNC_STARTED", type.name(), id, Map.of("attempt", attempt), Map.of(), Map.of());
    }

    private void auditSuccess(PersonType type, UUID id, ProviderSyncResult result) {
        auditService.record("INTELBRAS_SYNC_SUCCEEDED", type.name(), id,
                Map.of("message", safe(result.message()), "latencyMs", result.latency().toMillis()), Map.of(), Map.of());
    }

    private void auditFailure(PersonType type, UUID id, String error) {
        auditService.record("INTELBRAS_SYNC_FAILED", type.name(), id, Map.of("error", safe(error)), Map.of(), Map.of());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
