package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.Guest;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.mail.MailDeliveryResult;
import br.com.sport.accesscontrol.mail.MailService;
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
    private final DeviceRepository deviceRepository;
    private final AccessControlProvider provider;
    private final AuditService auditService;
    private final IntegrationEventPublisher eventPublisher;
    private final IntegrationSyncRealtimePublisher realtimePublisher;
    private final RealtimePublisherService systemRealtimePublisher;
    private final MailService mailService;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;

    public IntelbrasSyncWorker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                               DeviceRepository deviceRepository,
                               AccessControlProvider provider, AuditService auditService,
                               IntegrationEventPublisher eventPublisher,
                               IntegrationSyncRealtimePublisher realtimePublisher,
                               RealtimePublisherService systemRealtimePublisher,
                               MailService mailService,
                               MeterRegistry meterRegistry,
                               @Value("${app.integration.intelbras.sync.max-attempts:3}") int maxAttempts) {
        this.employeeRepository = employeeRepository;
        this.guestRepository = guestRepository;
        this.deviceRepository = deviceRepository;
        this.provider = provider;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.systemRealtimePublisher = systemRealtimePublisher;
        this.mailService = mailService;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = RabbitMqConfig.INTELBRAS_SYNC_QUEUE)
    public void consume(IntelbrasSyncMessage message) {
        process(message);
    }

    @Transactional
    public void process(IntelbrasSyncMessage message) {
        if (invalid(message)) {
            log.warn("intelbras_sync_worker_invalid_message message={}", message);
            return;
        }
        log.info("intelbras_sync_worker_start person_type={} person_id={} attempt={}",
                message.personType(), message.personId(), message.attempt());
        var sample = Timer.start(meterRegistry);
        try {
            ProviderSyncResult result = switch (message.personType()) {
                case EMPLOYEE -> syncEmployee(message.personId(), message.attempt());
                case GUEST -> syncGuest(message.personId(), message.attempt());
                case UNKNOWN -> new ProviderSyncResult(ProviderSyncStatus.FAILED, "Unknown person type cannot be synced.", java.time.Duration.ZERO);
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

    private ProviderSyncResult syncEmployee(UUID employeeId, int attempt) {
        var employee = employeeRepository.findById(employeeId).orElseThrow();
        if (attempt > 1 && employee.getSyncStatus() == SyncStatus.SYNCED) {
            log.info("intelbras_sync_employee_retry_skipped_already_synced employee_id={} attempt={}", employeeId, attempt);
            return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Employee already synced.", java.time.Duration.ZERO);
        }
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

    private ProviderSyncResult syncGuest(UUID guestId, int attempt) {
        var guest = guestRepository.findById(guestId).orElseThrow();
        if (attempt > 1 && guest.getSyncStatus() == SyncStatus.SYNCED) {
            log.info("intelbras_sync_guest_retry_skipped_already_synced guest_id={} attempt={}", guestId, attempt);
            return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Guest already synced.", java.time.Duration.ZERO);
        }
        var target = intelbrasTarget();
        var targetDeviceId = intelbrasTargetDeviceId();
        guest.markSyncing();
        auditStart(PersonType.GUEST, guestId, guest.getSyncAttempts());
        log.info("intelbras_sync_guest_start person_type={} guest_id={} device_id={} face_photo_configured={} attempt={}",
                PersonType.GUEST, guestId, targetDeviceId, guest.getFacePhotoUrl() != null && !guest.getFacePhotoUrl().isBlank(),
                guest.getSyncAttempts());
        realtimePublisher.publish(PersonType.GUEST, guestId, SyncStatus.SYNCING,
                "Sincronizando visitante " + guest.getFullName() + " com " + target);
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
        finishGuest(guest, result, target);
        return result;
    }

    private void finishEmployee(Employee employee, ProviderSyncResult result) {
        if (result.status() == ProviderSyncStatus.SUCCESS) {
            employee.markSynced();
            employeeRepository.save(employee);
            auditSuccess(PersonType.EMPLOYEE, employee.getId(), result);
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNCED, result.message());
        } else {
            employee.markSyncFailed(result.message());
            employeeRepository.save(employee);
            auditFailure(PersonType.EMPLOYEE, employee.getId(), result.message());
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNC_FAILED, result.message());
        }
    }

    private void finishGuest(Guest guest, ProviderSyncResult result, String target) {
        if (result.status() == ProviderSyncStatus.SUCCESS) {
            guest.markSynced();
            guestRepository.save(guest);
            auditSuccess(PersonType.GUEST, guest.getId(), result);
            sendGuestAccessApprovalEmail(guest);
            guestRepository.save(guest);
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNCED,
                    "Visitante " + guest.getFullName() + " sincronizado com " + target);
        } else {
            guest.markSyncFailed(result.message());
            guestRepository.save(guest);
            auditFailure(PersonType.GUEST, guest.getId(), result.message());
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNC_FAILED,
                    "Falha ao sincronizar visitante " + guest.getFullName() + " com " + target + ": " + safe(result.message()));
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

    private void sendGuestAccessApprovalEmail(Guest guest) {
        if (guest.hasAccessApprovedEmailBeenSent()) {
            log.info("guest_access_approval_email_skipped_already_sent guest_id={} sent_at={}",
                    guest.getId(), guest.getAccessApprovedEmailSentAt());
            return;
        }

        MailDeliveryResult delivery;
        try {
            delivery = mailService.sendGuestAccessApproved(guest);
        } catch (Exception exception) {
            delivery = MailDeliveryResult.failed(exception.getMessage());
        }

        guest.markAccessApprovedEmail(delivery.status(), safe(delivery.message()), delivery.sent());
        auditService.record("GUEST_ACCESS_APPROVAL_EMAIL_" + delivery.status(), "Guest", guest.getId(),
                Map.of("status", delivery.status(), "message", safe(delivery.message())), Map.of(), Map.of());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean invalid(IntelbrasSyncMessage message) {
        return message == null || message.personType() == null || message.personId() == null || message.attempt() < 1;
    }

    private String intelbrasTarget() {
        return deviceRepository.findAll().stream()
                .filter(this::eligibleIntelbrasDeviceForWorkerLog)
                .findFirst()
                .map(device -> device.getModel() == null || device.getModel().isBlank() ? device.getName() : device.getModel())
                .orElse("Intelbras");
    }

    private String intelbrasTargetDeviceId() {
        return deviceRepository.findAll().stream()
                .filter(this::eligibleIntelbrasDeviceForWorkerLog)
                .findFirst()
                .map(device -> device.getId() == null ? "unknown" : device.getId().toString())
                .orElse("none");
    }

    private boolean eligibleIntelbrasDeviceForWorkerLog(Device device) {
        return device != null
                && looksLikeIntelbrasDevice(device)
                && hasText(device.getIpAddress())
                && (device.getStatus() == DeviceStatus.ONLINE || device.getOnlineStatus() == DeviceStatus.ONLINE)
                && device.getArea() != null
                && hasText(device.getIntelbrasUsername())
                && hasText(device.getIntelbrasPassword());
    }

    private boolean looksLikeIntelbrasDevice(Device device) {
        return containsIntelbras(device.getModel())
                || containsIntelbras(device.getName())
                || containsIntelbrasSs55xx(device.getModel())
                || containsIntelbrasSs55xx(device.getName());
    }

    private boolean containsIntelbras(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("intelbras");
    }

    private boolean containsIntelbrasSs55xx(String value) {
        if (value == null) {
            return false;
        }
        var normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
        return normalized.contains("ss 5531")
                || normalized.contains("ss5531")
                || normalized.contains("ss 5541")
                || normalized.contains("ss5541");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
