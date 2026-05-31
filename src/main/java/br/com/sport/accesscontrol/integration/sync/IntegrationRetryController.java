package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.guests.GuestStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/integration/retry")
public class IntegrationRetryController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntegrationRetryController.class);

    private final IntegrationEventPublisher publisher;
    private final AuditService auditService;
    private final GuestRepository guestRepository;
    private final EmployeeRepository employeeRepository;

    /** Legacy / test constructor without repository dependencies. */
    public IntegrationRetryController(IntegrationEventPublisher publisher, AuditService auditService) {
        this(publisher, auditService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationRetryController(IntegrationEventPublisher publisher, AuditService auditService,
                                      GuestRepository guestRepository, EmployeeRepository employeeRepository) {
        this.publisher = publisher;
        this.auditService = auditService;
        this.guestRepository = guestRepository;
        this.employeeRepository = employeeRepository;
    }

    /** Re-enqueues a single person. */
    @PostMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> retry(@PathVariable String type, @PathVariable UUID id) {
        var personType = PersonType.valueOf(type.toUpperCase());
        log.info("intelbras_sync_manual_retry_requested person_type={} person_id={} attempt=1", personType, id);
        auditService.record("INTELBRAS_SYNC_MANUAL_RETRY", personType.name(), id, Map.of(), Map.of(), Map.of());
        publisher.publishIntelbrasSync(new IntelbrasSyncMessage(personType, id, 1));
        return Map.of("status", "queued", "type", personType.name(), "id", id.toString());
    }

    /**
     * Bulk re-sync: enqueues all PENDING_SYNC and SYNC_FAILED guests (status=COMPLETED)
     * and employees (status=ACTIVE) that have not yet reached a terminal SYNCED state.
     *
     * <p>Uses direct queue publishing (bypasses service validation) so records with
     * missing areas still get a sync attempt — the worker handles the failure gracefully.
     */
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> bulkRetry() {
        var retryStatuses = List.of(SyncStatus.PENDING_SYNC, SyncStatus.SYNC_FAILED);

        var guests = guestRepository.findByStatusAndSyncStatusIn(GuestStatus.COMPLETED, retryStatuses);
        var employees = employeeRepository.findByStatusAndSyncStatusIn(EmployeeStatus.ACTIVE, retryStatuses);

        log.info("BULK_SYNC_REQUESTED guests_eligible={} employees_eligible={}",
                guests.size(), employees.size());

        var guestQueued = 0;
        var guestSkipped = 0;
        for (var guest : guests) {
            try {
                publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
                log.info("BULK_SYNC_ENQUEUED person_type=GUEST person_id={} cpf={} sync_status={}",
                        guest.getId(), guest.getCpf(), guest.getSyncStatus());
                guestQueued++;
            } catch (Exception ex) {
                log.warn("BULK_SYNC_SKIP person_type=GUEST person_id={} reason={}", guest.getId(), ex.getMessage());
                guestSkipped++;
            }
        }

        var employeeQueued = 0;
        var employeeSkipped = 0;
        for (var employee : employees) {
            try {
                publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));
                log.info("BULK_SYNC_ENQUEUED person_type=EMPLOYEE person_id={} cpf={} sync_status={}",
                        employee.getId(), employee.getCpf(), employee.getSyncStatus());
                employeeQueued++;
            } catch (Exception ex) {
                log.warn("BULK_SYNC_SKIP person_type=EMPLOYEE person_id={} reason={}", employee.getId(), ex.getMessage());
                employeeSkipped++;
            }
        }

        log.info("BULK_SYNC_COMPLETED guests_queued={} guests_skipped={} employees_queued={} employees_skipped={}",
                guestQueued, guestSkipped, employeeQueued, employeeSkipped);
        auditService.record("INTELBRAS_BULK_SYNC_REQUESTED", "System", null,
                Map.of("guestsQueued", guestQueued, "employeesQueued", employeeQueued,
                        "guestsSkipped", guestSkipped, "employeesSkipped", employeeSkipped),
                Map.of(), Map.of());

        return Map.of(
                "status", "queued",
                "guestsQueued", guestQueued,
                "guestsSkipped", guestSkipped,
                "employeesQueued", employeeQueued,
                "employeesSkipped", employeeSkipped,
                "totalQueued", guestQueued + employeeQueued
        );
    }

    /**
     * Force re-sync ALL users regardless of current syncStatus (includes SYNCED).
     *
     * <p>Use this after a CardNo strategy change to rebuild all records in the controller
     * with the new CardNo. Unlike {@link #bulkRetry()}, this endpoint ignores the current
     * sync status and enqueues every COMPLETED guest and ACTIVE employee.
     *
     * <p>Safe to run multiple times — the worker skips users already synced at attempt > 1
     * only when status is SYNCED at that point, so duplicate messages are harmless.
     *
     * <p>Recommendation over Opção B (SQL reset): does not modify DB state before sync
     * starts, avoiding mass-PENDING_SYNC display on the frontend during processing.
     */
    @PostMapping("/bulk-force")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> bulkForceRetry() {
        var guests = guestRepository.findAll().stream()
                .filter(g -> g.getStatus() == GuestStatus.COMPLETED)
                .toList();
        var employees = employeeRepository.findAll().stream()
                .filter(e -> e.getStatus() == EmployeeStatus.ACTIVE)
                .toList();

        log.info("BULK_FORCE_SYNC_REQUESTED guests_total={} employees_total={}",
                guests.size(), employees.size());

        var guestQueued = 0;
        var guestSkipped = 0;
        for (var guest : guests) {
            try {
                publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
                log.info("BULK_FORCE_SYNC_ENQUEUED person_type=GUEST person_id={} cpf={} sync_status={}",
                        guest.getId(), guest.getCpf(), guest.getSyncStatus());
                guestQueued++;
            } catch (Exception ex) {
                log.warn("BULK_FORCE_SYNC_SKIP person_type=GUEST person_id={} reason={}", guest.getId(), ex.getMessage());
                guestSkipped++;
            }
        }

        var employeeQueued = 0;
        var employeeSkipped = 0;
        for (var employee : employees) {
            try {
                publisher.publishIntelbrasSync(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));
                log.info("BULK_FORCE_SYNC_ENQUEUED person_type=EMPLOYEE person_id={} cpf={} sync_status={}",
                        employee.getId(), employee.getCpf(), employee.getSyncStatus());
                employeeQueued++;
            } catch (Exception ex) {
                log.warn("BULK_FORCE_SYNC_SKIP person_type=EMPLOYEE person_id={} reason={}", employee.getId(), ex.getMessage());
                employeeSkipped++;
            }
        }

        log.info("BULK_FORCE_SYNC_COMPLETED guests_queued={} employees_queued={} total={}",
                guestQueued, employeeQueued, guestQueued + employeeQueued);
        auditService.record("INTELBRAS_BULK_FORCE_SYNC_REQUESTED", "System", null,
                Map.of("guestsQueued", guestQueued, "employeesQueued", employeeQueued,
                        "totalQueued", guestQueued + employeeQueued),
                Map.of(), Map.of());

        return Map.of(
                "status", "queued",
                "guestsQueued", guestQueued,
                "guestsSkipped", guestSkipped,
                "employeesQueued", employeeQueued,
                "employeesSkipped", employeeSkipped,
                "totalQueued", guestQueued + employeeQueued
        );
    }
}
