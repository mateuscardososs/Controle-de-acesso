package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.guests.Guest;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.guests.GuestStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Selects people stuck in PENDING_SYNC / SYNCING / recoverable SYNC_FAILED and atomically "claims"
 * them (transition to SYNCING + bump updated_at) so they can be re-enqueued. The claim runs in a
 * single transaction with {@code FOR UPDATE SKIP LOCKED}, so concurrent reapers never grab the same
 * row. Publishing is intentionally NOT done here — the caller publishes after the transaction commits.
 */
@Service
public class IntelbrasSyncReaperService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncReaperService.class);
    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Recife");

    private final EmployeeRepository employeeRepository;
    private final GuestRepository guestRepository;
    private final Clock clock;

    @Autowired
    public IntelbrasSyncReaperService(EmployeeRepository employeeRepository, GuestRepository guestRepository) {
        this(employeeRepository, guestRepository, Clock.systemUTC());
    }

    IntelbrasSyncReaperService(EmployeeRepository employeeRepository, GuestRepository guestRepository, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.guestRepository = guestRepository;
        this.clock = clock;
    }

    @Transactional
    public ClaimResult claim(IntelbrasProperties.SyncReaper cfg) {
        var now = Instant.now(clock);
        var pendingBefore = now.minus(cfg.getPendingThreshold());
        var syncingBefore = now.minus(cfg.getSyncingThreshold());
        var failedBefore = now.minus(cfg.getFailedThreshold());
        var page = PageRequest.of(0, Math.max(1, cfg.getBatchSize()));
        var acc = new Accumulator();

        var employeePending = employeeRepository.findReapableBySyncStatus(SyncStatus.PENDING_SYNC, pendingBefore, page);
        var employeeSyncing = employeeRepository.findReapableBySyncStatus(SyncStatus.SYNCING, syncingBefore, page);
        var employeeFailed = employeeRepository.findReapableBySyncStatus(SyncStatus.SYNC_FAILED, failedBefore, page);
        var guestPending = guestRepository.findReapableBySyncStatus(SyncStatus.PENDING_SYNC, pendingBefore, page);
        var guestSyncing = guestRepository.findReapableBySyncStatus(SyncStatus.SYNCING, syncingBefore, page);
        var guestFailed = guestRepository.findReapableBySyncStatus(SyncStatus.SYNC_FAILED, failedBefore, page);

        acc.pendingStuck = employeePending.size() + guestPending.size();
        acc.syncingStuck = employeeSyncing.size() + guestSyncing.size();

        reapEmployees(employeePending, "PENDING", false, cfg, now, acc);
        reapEmployees(employeeSyncing, "SYNCING", false, cfg, now, acc);
        reapEmployees(employeeFailed, "FAILED", true, cfg, now, acc);
        reapGuests(guestPending, "PENDING", false, cfg, now, acc);
        reapGuests(guestSyncing, "SYNCING", false, cfg, now, acc);
        reapGuests(guestFailed, "FAILED", true, cfg, now, acc);

        return new ClaimResult(List.copyOf(acc.toPublish), acc.requeued, acc.skipped,
                acc.pendingStuck, acc.syncingStuck, acc.failedRetriable);
    }

    private void reapEmployees(List<Employee> rows, String category, boolean isFailed,
                               IntelbrasProperties.SyncReaper cfg, Instant now, Accumulator acc) {
        for (Employee employee : rows) {
            var reason = employeeSkipReason(employee, isFailed, cfg);
            if (reason != null) {
                skip(PersonType.EMPLOYEE, employee.getId(), category, reason, acc);
                continue;
            }
            if (isFailed) {
                acc.failedRetriable++;
            }
            employee.markSyncing();
            employeeRepository.save(employee);
            requeue(PersonType.EMPLOYEE, employee.getId(), category, acc);
        }
    }

    private void reapGuests(List<Guest> rows, String category, boolean isFailed,
                            IntelbrasProperties.SyncReaper cfg, Instant now, Accumulator acc) {
        for (Guest guest : rows) {
            var reason = guestSkipReason(guest, isFailed, cfg, now);
            if (reason != null) {
                skip(PersonType.GUEST, guest.getId(), category, reason, acc);
                continue;
            }
            if (isFailed) {
                acc.failedRetriable++;
            }
            guest.markSyncing();
            guestRepository.save(guest);
            requeue(PersonType.GUEST, guest.getId(), category, acc);
        }
    }

    private String employeeSkipReason(Employee employee, boolean isFailed, IntelbrasProperties.SyncReaper cfg) {
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            return employee.getStatus() == EmployeeStatus.BLOCKED ? "BLOCKED" : "INACTIVE";
        }
        // The worker derives a CardNo from the CPF when none exists, so an ACTIVE employee with a CPF
        // always has at least one identifier. Only block when there is no face, no card and no CPF.
        if (isBlank(employee.getFacePhotoUrl()) && isBlank(employee.getIntelbrasCardNo()) && isBlank(employee.getCpf())) {
            return "NO_IDENTIFIER";
        }
        if (isFailed && employee.getSyncAttempts() >= cfg.getMaxFailedRequeues()) {
            return "MAX_REQUEUES";
        }
        return null;
    }

    private String guestSkipReason(Guest guest, boolean isFailed, IntelbrasProperties.SyncReaper cfg, Instant now) {
        if (guest.getStatus() != GuestStatus.COMPLETED) {
            return "NOT_COMPLETED";
        }
        if (isBlank(guest.getFacePhotoUrl())) {
            return "NO_FACE";
        }
        var validUntil = guestValidUntil(guest);
        if (validUntil != null && validUntil.isBefore(now)) {
            return "EXPIRED";
        }
        if (isFailed && guest.getSyncAttempts() >= cfg.getMaxFailedRequeues()) {
            return "MAX_REQUEUES";
        }
        return null;
    }

    private Instant guestValidUntil(Guest guest) {
        if (guest.getInvitedDay() != null) {
            return guest.getInvitedDay().plusDays(1).atTime(LocalTime.of(4, 0)).atZone(EVENT_ZONE).toInstant();
        }
        return guest.getVisitEnd();
    }

    private void requeue(PersonType type, UUID id, String category, Accumulator acc) {
        acc.toPublish.add(new IntelbrasSyncMessage(type, id, 1));
        acc.requeued++;
        log.info("SYNC_REAPER_REQUEUED personType={} personId={} category={}", type, id, category);
    }

    private void skip(PersonType type, UUID id, String category, String reason, Accumulator acc) {
        acc.skipped++;
        log.info("SYNC_REAPER_SKIPPED personType={} personId={} category={} reason={}", type, id, category, reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Accumulator {
        private final List<IntelbrasSyncMessage> toPublish = new ArrayList<>();
        private int requeued;
        private int skipped;
        private long pendingStuck;
        private long syncingStuck;
        private long failedRetriable;
    }

    /** Outcome of one reaper claim pass. {@code toPublish} must be published after the transaction commits. */
    public record ClaimResult(
            List<IntelbrasSyncMessage> toPublish,
            int requeued,
            int skipped,
            long pendingStuck,
            long syncingStuck,
            long failedRetriable
    ) {
    }
}
