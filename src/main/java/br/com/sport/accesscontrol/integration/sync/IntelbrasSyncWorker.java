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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Service
public class IntelbrasSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncWorker.class);
    private static final int PERSON_SYNC_LOCK_STRIPES = 64;

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
    private final br.com.sport.accesscontrol.areas.LoungeAreaResolver loungeAreaResolver;
    private final Object[] personSyncLocks = createPersonSyncLocks();

    /** Legacy / test constructor without LoungeAreaResolver or generator. */
    public IntelbrasSyncWorker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                               DeviceRepository deviceRepository,
                               AccessControlProvider provider, AuditService auditService,
                               IntegrationEventPublisher eventPublisher,
                               IntegrationSyncRealtimePublisher realtimePublisher,
                               RealtimePublisherService systemRealtimePublisher,
                               MailService mailService,
                               MeterRegistry meterRegistry,
                               @Value("${app.integration.intelbras.sync.max-attempts:3}") int maxAttempts) {
        this(employeeRepository, guestRepository, deviceRepository, provider, auditService, eventPublisher,
                realtimePublisher, systemRealtimePublisher, mailService, meterRegistry, maxAttempts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IntelbrasSyncWorker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                               DeviceRepository deviceRepository,
                               AccessControlProvider provider, AuditService auditService,
                               IntegrationEventPublisher eventPublisher,
                               IntegrationSyncRealtimePublisher realtimePublisher,
                               RealtimePublisherService systemRealtimePublisher,
                               MailService mailService,
                               MeterRegistry meterRegistry,
                               @Value("${app.integration.intelbras.sync.max-attempts:3}") int maxAttempts,
                               br.com.sport.accesscontrol.areas.LoungeAreaResolver loungeAreaResolver) {
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
        this.loungeAreaResolver = loungeAreaResolver;
    }

    @RabbitListener(queues = RabbitMqConfig.INTELBRAS_SYNC_QUEUE)
    public void consume(IntelbrasSyncMessage message) {
        log.info("GUEST_SYNC_WORKER_RECEIVED person_type={} person_id={} attempt={}",
                message == null ? "null" : message.personType(),
                message == null ? "null" : message.personId(),
                message == null ? 0 : message.attempt());
        log.info("SYNC_EVENT_CONSUMED person_type={} person_id={} attempt={}",
                message == null ? "null" : message.personType(),
                message == null ? "null" : message.personId(),
                message == null ? 0 : message.attempt());
        process(message);
    }

    public void process(IntelbrasSyncMessage message) {
        if (invalid(message)) {
            log.warn("intelbras_sync_worker_invalid_message message={}", message);
            return;
        }
        synchronized (personSyncLock(message)) {
            processLocked(message);
        }
    }

    private void processLocked(IntelbrasSyncMessage message) {
        log.info("SYNC_WORKER_STARTED person_type={} person_id={} attempt={}",
                message.personType(), message.personId(), message.attempt());
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
            log.info("GUEST_SYNC_WORKER_FINISHED person_type={} person_id={} attempt={} result={} success={} failed={} total={}",
                    message.personType(), message.personId(), message.attempt(),
                    result.status().name(), result.successCount(), result.failedCount(), result.totalTargets());
            var finalStatus = finalSyncStatus(result);
            if (finalStatus == SyncStatus.SYNCED) {
                meterRegistry.counter("intelbras.sync.success.count", "type", message.personType().name()).increment();
                return;
            }
            if (finalStatus == SyncStatus.SYNCED_WITH_WARNINGS) {
                meterRegistry.counter("intelbras.sync.partial.count", "type", message.personType().name()).increment();
                return;
            }
            handleFailure(message, result.message());
        } catch (Exception exception) {
            sample.stop(meterRegistry.timer("intelbras.sync.latency", "result", "EXCEPTION"));
            log.error("GUEST_SYNC_WORKER_FAILED person_type={} person_id={} attempt={} exception_type={} error={}",
                    message.personType(), message.personId(), message.attempt(),
                    exception.getClass().getSimpleName(), safe(exception.getMessage()));
            log.error("SYNC_WORKER_FAILED_BEFORE_PROVIDER person_type={} person_id={} attempt={} exception_type={} error={}",
                    message.personType(), message.personId(), message.attempt(),
                    exception.getClass().getSimpleName(), safe(exception.getMessage()), exception);
            handleFailure(message, exception.getClass().getSimpleName() + ": " + safe(exception.getMessage()));
        }
    }

    private ProviderSyncResult syncEmployee(UUID employeeId, int attempt) {
        var employee = employeeRepository.findByIdWithAllowedAreas(employeeId).orElseThrow();
        if (attempt > 1 && employee.getSyncStatus() == SyncStatus.SYNCED) {
            log.info("intelbras_sync_employee_retry_skipped_already_synced employee_id={} attempt={}", employeeId, attempt);
            return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Employee already synced.", java.time.Duration.ZERO);
        }
        employee.markSyncing();
        auditStart(PersonType.EMPLOYEE, employeeId, employee.getSyncAttempts());
        realtimePublisher.publish(PersonType.EMPLOYEE, employeeId, SyncStatus.SYNCING, "Sincronizando Intelbras");
        java.util.Set<java.util.UUID> allowedEmployeeAreas = employee.getAllowedAreas() == null
                ? java.util.Collections.<java.util.UUID>emptySet()
                : employee.getAllowedAreas().stream()
                        .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                        .map(br.com.sport.accesscontrol.areas.Area::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        var result = provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE,
                employee.getId(),
                employee.getCpf(),
                employee.getCardNo(),
                employee.getFullName(),
                employee.getFacePhotoUrl(),
                employee.getStatus() == br.com.sport.accesscontrol.employees.EmployeeStatus.ACTIVE,
                employee.getAccessValidFrom(),
                employee.getAccessValidUntil(),
                null,
                allowedEmployeeAreas
        ));
        finishEmployee(employee, result);
        return result;
    }

    private ProviderSyncResult syncGuest(UUID guestId, int attempt) {
        var guest = guestRepository.findByIdWithAllowedAreas(guestId).orElseThrow();
        if (attempt > 1 && guest.getSyncStatus() == SyncStatus.SYNCED) {
            log.info("intelbras_sync_guest_retry_skipped_already_synced guest_id={} attempt={}", guestId, attempt);
            return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Guest already synced.", java.time.Duration.ZERO);
        }
        log.info("manual_sync_person_loaded person_type=GUEST person_id={} cpf={} status={} invited_lounge={} allowed_areas_count={} face_photo_configured={} attempt={}",
                guestId, guest.getCpf(), guest.getStatus(), guest.getInvitedLounge(),
                guest.getAllowedAreas() == null ? 0 : guest.getAllowedAreas().size(),
                guest.getFacePhotoUrl() != null && !guest.getFacePhotoUrl().isBlank(), attempt);
        var target = intelbrasTarget();
        var targetDeviceId = intelbrasTargetDeviceId();
        guest.markSyncing();
        auditStart(PersonType.GUEST, guestId, guest.getSyncAttempts());
        log.info("intelbras_sync_guest_start person_type={} guest_id={} device_id={} face_photo_configured={} attempt={}",
                PersonType.GUEST, guestId, targetDeviceId, guest.getFacePhotoUrl() != null && !guest.getFacePhotoUrl().isBlank(),
                guest.getSyncAttempts());
        realtimePublisher.publish(PersonType.GUEST, guestId, SyncStatus.SYNCING,
                "Sincronizando visitante " + guest.getFullName() + " com " + target);
        var validFrom = guestValidFrom(guest);
        var validUntil = guestValidUntil(guest);
        log.info("intelbras_sync_guest_validity guest_id={} invited_day={} valid_from={} valid_until={}",
                guestId, guest.getInvitedDay(), validFrom, validUntil);
        java.util.Set<java.util.UUID> allowedGuestAreas = guest.getAllowedAreas() == null
                ? new java.util.LinkedHashSet<>()
                : guest.getAllowedAreas().stream()
                        .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                        .map(br.com.sport.accesscontrol.areas.Area::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        String areaNames = guest.getAllowedAreas() == null ? "" :
                guest.getAllowedAreas().stream()
                        .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                        .map(br.com.sport.accesscontrol.areas.Area::getName)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining(","));
        log.info("manual_sync_allowed_areas person_type=GUEST person_id={} cpf={} area_ids=[{}] area_names=[{}] areas_count={}",
                guestId, guest.getCpf(),
                allowedGuestAreas.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")),
                areaNames, allowedGuestAreas.size());
        log.info("SYNC_GUEST_SNAPSHOT guest_id={} name={} cpf_present={} sync_status={} sync_attempts={} invited_lounge={} allowed_area_ids=[{}] allowed_area_names=[{}] face_present={}",
                guestId, guest.getFullName(), guest.getCpf() != null,
                guest.getSyncStatus(), guest.getSyncAttempts(), guest.getInvitedLounge(),
                allowedGuestAreas.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")),
                areaNames, guest.getFacePhotoUrl() != null && !guest.getFacePhotoUrl().isBlank());
        var targetMode = br.com.sport.accesscontrol.appconfig.LoungeConfig.COLLABORATOR_LOUNGE
                .equalsIgnoreCase(guest.getInvitedLounge()) ? "ALL_ACTIVE_DEVICES" : "AREA_BASED";
        log.info("GUEST_SYNC_TARGET_MODE guest_id={} invited_lounge={} target_mode={} target_count={} area_ids=[{}] area_names=[{}]",
                guestId, guest.getInvitedLounge(), targetMode, allowedGuestAreas.size(),
                allowedGuestAreas.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")),
                areaNames);
        if (allowedGuestAreas.isEmpty() && loungeAreaResolver != null) {
            // Recalculate areas from invitedLounge — handles guests registered before area config
            // or guests whose guest_allowed_areas is empty/incorrect.
            log.warn("GUEST_SYNC_RECALCULATING_AREAS guest_id={} cpf={} invited_lounge={} — stored areas empty, recalculating from lounge resolver",
                    guestId, guest.getCpf(), guest.getInvitedLounge());
            var recalculated = loungeAreaResolver.resolveForLounge(guest.getInvitedLounge());
            allowedGuestAreas = recalculated.stream()
                    .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                    .map(br.com.sport.accesscontrol.areas.Area::getId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            areaNames = recalculated.stream()
                    .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                    .map(br.com.sport.accesscontrol.areas.Area::getName)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining(","));
            log.info("GUEST_SYNC_RECALCULATED_AREAS guest_id={} cpf={} invited_lounge={} recalc_area_count={} recalc_area_names=[{}]",
                    guestId, guest.getCpf(), guest.getInvitedLounge(), allowedGuestAreas.size(), areaNames);
        }

        if (allowedGuestAreas.isEmpty()) {
            var noTargetsMessage = "Nenhuma catraca encontrada para o camarote selecionado: " + guest.getInvitedLounge();
            log.warn("SYNC_PROVIDER_NOT_CALLED_REASON guest_id={} reason=no_allowed_areas invited_lounge={} cpf={} sync_status={} message={}",
                    guestId, guest.getInvitedLounge(), guest.getCpf(), guest.getSyncStatus(),
                    noTargetsMessage);
            var result = new ProviderSyncResult(
                    ProviderSyncStatus.FAILED,
                    noTargetsMessage,
                    java.time.Duration.ZERO,
                    0, 0, 0, 0
            );
            finishGuest(guest, result, target);
            return result;
        }
        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST,
                guest.getId(),
                guest.getCpf(),
                null,
                guest.getFullName(),
                guest.getFacePhotoUrl(),
                guest.getStatus() == br.com.sport.accesscontrol.guests.GuestStatus.COMPLETED,
                validFrom,
                validUntil,
                null,
                allowedGuestAreas
        ));
        finishGuest(guest, result, target);
        return result;
    }

    private void finishEmployee(Employee employee, ProviderSyncResult result) {
        var finalStatus = finalSyncStatus(result);
        if (finalStatus == SyncStatus.SYNCED) {
            employee.markSynced(result.totalTargets(), result.successCount(), result.failedCount(), result.skippedCount());
            employeeRepository.save(employee);
            log.info("SYNC_STATUS_UPDATED person_type=EMPLOYEE person_id={} sync_status=SYNCED success_count={} total_targets={} failed_count={} skipped_count={}",
                    employee.getId(), result.successCount(), result.totalTargets(), result.failedCount(), result.skippedCount());
            auditSuccess(PersonType.EMPLOYEE, employee.getId(), result);
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNCED, result.message());
        } else if (finalStatus == SyncStatus.SYNCED_WITH_WARNINGS) {
            employee.markSyncedWithWarnings(result.message(), result.totalTargets(), result.successCount(),
                    result.failedCount(), result.skippedCount());
            employeeRepository.save(employee);
            log.warn("SYNC_STATUS_UPDATED person_type=EMPLOYEE person_id={} sync_status=SYNCED_WITH_WARNINGS success_count={} total_targets={} failed_count={} skipped_count={} warning={}",
                    employee.getId(), result.successCount(), result.totalTargets(), result.failedCount(),
                    result.skippedCount(), safe(result.message()));
            auditPartial(PersonType.EMPLOYEE, employee.getId(), result);
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNCED_WITH_WARNINGS, result.message());
        } else {
            employee.markSyncFailed(result.message(), result.totalTargets(), result.successCount(),
                    result.failedCount(), result.skippedCount());
            employeeRepository.save(employee);
            auditFailure(PersonType.EMPLOYEE, employee.getId(), result);
            realtimePublisher.publish(PersonType.EMPLOYEE, employee.getId(), SyncStatus.SYNC_FAILED, result.message());
        }
    }

    private void finishGuest(Guest guest, ProviderSyncResult result, String target) {
        var finalStatus = finalSyncStatus(result);
        if (finalStatus == SyncStatus.SYNCED) {
            guest.markSynced(result.totalTargets(), result.successCount(), result.failedCount(), result.skippedCount());
            guestRepository.save(guest);
            log.info("SYNC_STATUS_UPDATED person_type=GUEST person_id={} cpf={} sync_status=SYNCED sync_attempts={} last_sync_at={} success_count={} total_targets={} failed_count={} skipped_count={}",
                    guest.getId(), guest.getCpf(), guest.getSyncAttempts(), guest.getLastSyncAt(),
                    result.successCount(), result.totalTargets(), result.failedCount(), result.skippedCount());
            auditSuccess(PersonType.GUEST, guest.getId(), result);
            auditGuestSyncResult(guest, target, "SYNCED", null);
            sendGuestAccessApprovalEmail(guest);
            guestRepository.save(guest);
            log.info("manual_sync_finished person_type=GUEST person_id={} cpf={} result={} target={} latency_ms={}",
                    guest.getId(), guest.getCpf(), result.status().name(), target, result.latency().toMillis());
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNCED,
                    "Visitante " + guest.getFullName() + ": " + result.message());
        } else if (finalStatus == SyncStatus.SYNCED_WITH_WARNINGS) {
            guest.markSyncedWithWarnings(result.message(), result.totalTargets(), result.successCount(),
                    result.failedCount(), result.skippedCount());
            guestRepository.save(guest);
            log.warn("SYNC_STATUS_UPDATED person_type=GUEST person_id={} cpf={} sync_status=SYNCED_WITH_WARNINGS sync_attempts={} last_sync_at={} success_count={} total_targets={} failed_count={} skipped_count={} warning={}",
                    guest.getId(), guest.getCpf(), guest.getSyncAttempts(), guest.getLastSyncAt(),
                    result.successCount(), result.totalTargets(), result.failedCount(), result.skippedCount(),
                    safe(result.message()));
            auditPartial(PersonType.GUEST, guest.getId(), result);
            auditGuestSyncResult(guest, target, "SYNCED_WITH_WARNINGS", result.message());
            log.warn("manual_sync_partial person_type=GUEST person_id={} cpf={} result=SYNCED_WITH_WARNINGS target={} warning={} latency_ms={}",
                    guest.getId(), guest.getCpf(), target, safe(result.message()), result.latency().toMillis());
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNCED_WITH_WARNINGS,
                    result.message());
        } else {
            guest.markSyncFailed(result.message(), result.totalTargets(), result.successCount(),
                    result.failedCount(), result.skippedCount());
            guestRepository.save(guest);
            log.info("SYNC_STATUS_UPDATED person_type=GUEST person_id={} cpf={} sync_status=SYNC_FAILED sync_attempts={} last_sync_error={} success_count={} total_targets={} failed_count={} skipped_count={}",
                    guest.getId(), guest.getCpf(), guest.getSyncAttempts(), safe(result.message()),
                    result.successCount(), result.totalTargets(), result.failedCount(), result.skippedCount());
            auditFailure(PersonType.GUEST, guest.getId(), result);
            auditGuestSyncResult(guest, target, "SYNC_FAILED", result.message());
            log.warn("manual_sync_failed person_type=GUEST person_id={} cpf={} result=SYNC_FAILED target={} error={} latency_ms={}",
                    guest.getId(), guest.getCpf(), target, safe(result.message()), result.latency().toMillis());
            realtimePublisher.publish(PersonType.GUEST, guest.getId(), SyncStatus.SYNC_FAILED,
                    "Falha ao sincronizar visitante " + guest.getFullName() + " com " + target + ": " + safe(result.message()));
        }
    }

    private SyncStatus finalSyncStatus(ProviderSyncResult result) {
        var totalTargets = Math.max(0, result.totalTargets());
        var successCount = Math.max(0, result.successCount());
        var failedCount = Math.max(0, result.failedCount());
        var skippedCount = Math.max(0, result.skippedCount());
        if (totalTargets > 0 && successCount == totalTargets && failedCount == 0 && skippedCount == 0) {
            return SyncStatus.SYNCED;
        }
        // SYNCED_WITH_WARNINGS só é válido quando PELO MENOS uma controladora confirmou.
        // 0 confirmadas (mesmo que apenas "não verificadas") é sempre SYNC_FAILED — nunca warnings.
        if (totalTargets > 0 && successCount > 0 && successCount < totalTargets) {
            return SyncStatus.SYNCED_WITH_WARNINGS;
        }
        return SyncStatus.SYNC_FAILED;
    }

    private void auditGuestSyncResult(Guest guest, String target, String result, String error) {
        auditService.record("GUEST_SYNC_RESULT", "Guest", guest.getId(),
                Map.of(
                        "visitor", guest.getFullName(),
                        "target", safe(target),
                        "result", result,
                        "error", safe(error),
                        "validFrom", guest.getVisitStart(),
                        "validUntil", guest.getVisitEnd()
                ),
                Map.of(),
                Map.of());
    }

    private void handleFailure(IntelbrasSyncMessage message, String error) {
        meterRegistry.counter("intelbras.sync.failed.count", "type", message.personType().name()).increment();
        if (message.attempt() < maxAttempts) {
            meterRegistry.counter("intelbras.sync.retry.count", "type", message.personType().name()).increment();
            log.warn("SYNC_RETRY_SCHEDULED_WITH_REASON person_type={} person_id={} attempt={} max_attempts={} reason={}",
                    message.personType(), message.personId(), message.attempt(), maxAttempts, safe(error));
            auditService.record("INTELBRAS_SYNC_RETRY", message.personType().name(), message.personId(),
                    Map.of("attempt", message.attempt(), "error", safe(error)), Map.of(), Map.of());
            eventPublisher.publishIntelbrasSync(message.nextAttempt());
            return;
        }
        log.error("SYNC_RETRY_EXHAUSTED person_type={} person_id={} total_attempts={} last_error={}",
                message.personType(), message.personId(), message.attempt(), safe(error));
        systemRealtimePublisher.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.ERROR,
                "Falha de sincronização Intelbras",
                message.personType() + " " + message.personId() + " excedeu tentativas de sync. Motivo: " + safe(error),
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
                Map.of(
                        "message", safe(result.message()),
                        "latencyMs", result.latency().toMillis(),
                        "totalTargets", result.totalTargets(),
                        "successCount", result.successCount(),
                        "failedCount", result.failedCount(),
                        "skippedCount", result.skippedCount()
                ), Map.of(), Map.of());
    }

    private void auditPartial(PersonType type, UUID id, ProviderSyncResult result) {
        auditService.record("INTELBRAS_SYNC_PARTIAL", type.name(), id,
                Map.of(
                        "message", safe(result.message()),
                        "latencyMs", result.latency().toMillis(),
                        "totalTargets", result.totalTargets(),
                        "successCount", result.successCount(),
                        "failedCount", result.failedCount(),
                        "skippedCount", result.skippedCount()
                ), Map.of(), Map.of());
    }

    private void auditFailure(PersonType type, UUID id, ProviderSyncResult result) {
        auditService.record("INTELBRAS_SYNC_FAILED", type.name(), id,
                Map.of(
                        "error", safe(result.message()),
                        "latencyMs", result.latency().toMillis(),
                        "totalTargets", result.totalTargets(),
                        "successCount", result.successCount(),
                        "failedCount", result.failedCount(),
                        "skippedCount", result.skippedCount()
                ), Map.of(), Map.of());
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

    private static Object[] createPersonSyncLocks() {
        var locks = new Object[PERSON_SYNC_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object personSyncLock(IntelbrasSyncMessage message) {
        var hash = java.util.Objects.hash(message.personType(), message.personId());
        return personSyncLocks[Math.floorMod(hash, personSyncLocks.length)];
    }

    private String intelbrasTarget() {
        return devicesWithAreaForWorkerLog().stream()
                .filter(this::eligibleIntelbrasDeviceForWorkerLog)
                .findFirst()
                .map(device -> device.getModel() == null || device.getModel().isBlank() ? device.getName() : device.getModel())
                .orElse("Intelbras");
    }

    private String intelbrasTargetDeviceId() {
        return devicesWithAreaForWorkerLog().stream()
                .filter(this::eligibleIntelbrasDeviceForWorkerLog)
                .findFirst()
                .map(device -> device.getId() == null ? "unknown" : device.getId().toString())
                .orElse("none");
    }

    private java.util.List<Device> devicesWithAreaForWorkerLog() {
        var devices = deviceRepository.findAllWithArea();
        if (devices == null || devices.isEmpty()) {
            return deviceRepository.findAll();
        }
        return devices;
    }

    private boolean eligibleIntelbrasDeviceForWorkerLog(Device device) {
        return device != null
                && device.isActive()
                && looksLikeIntelbrasDevice(device)
                && hasText(device.getIpAddress())
                && (device.getStatus() == DeviceStatus.ONLINE || device.getOnlineStatus() == DeviceStatus.ONLINE)
                && device.getArea() != null
                && hasText(device.getIntelbrasUsername())
                && hasText(device.getIntelbrasPassword());
    }

    private boolean looksLikeIntelbrasDevice(Device device) {
        if (hasText(device.getIntelbrasPassword())) {
            return true;
        }
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

    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Recife");

    private Instant guestValidFrom(br.com.sport.accesscontrol.guests.Guest guest) {
        if (guest.getInvitedDay() != null) {
            return guest.getInvitedDay().atTime(LocalTime.of(15, 0)).atZone(EVENT_ZONE).toInstant();
        }
        return guest.getVisitStart();
    }

    private Instant guestValidUntil(br.com.sport.accesscontrol.guests.Guest guest) {
        if (guest.getInvitedDay() != null) {
            return guest.getInvitedDay().plusDays(1).atTime(LocalTime.of(4, 0)).atZone(EVENT_ZONE).toInstant();
        }
        return guest.getVisitEnd();
    }
}
