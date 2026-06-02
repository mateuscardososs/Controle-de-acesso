package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.guests.Guest;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.guests.GuestStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelbrasSyncReaperServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-02T12:00:00Z");

    private EmployeeRepository employeeRepository;
    private GuestRepository guestRepository;
    private IntelbrasSyncReaperService service;
    private IntelbrasProperties.SyncReaper cfg;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        guestRepository = mock(GuestRepository.class);
        service = new IntelbrasSyncReaperService(employeeRepository, guestRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        cfg = new IntelbrasProperties.SyncReaper(); // defaults: pending 5m, syncing 10m, failed 10m, batch 100, max 5
    }

    @Test
    void pendingOldEmployeeIsRequeuedAndClaimedAsSyncing() {
        var emp = employee(EmployeeStatus.ACTIVE, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any())).thenReturn(List.of(emp));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).containsExactly(new IntelbrasSyncMessage(PersonType.EMPLOYEE, emp.getId(), 1));
        assertThat(emp.getSyncStatus()).isEqualTo(SyncStatus.SYNCING);
        verify(employeeRepository).save(emp);
    }

    @Test
    void thresholdsUsePendingSyncingAndFailedWindows() {
        service.claim(cfg);

        verify(employeeRepository).findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC),
                eq(NOW.minusSeconds(300)), any());
        verify(employeeRepository).findReapableBySyncStatus(eq(SyncStatus.SYNCING),
                eq(NOW.minusSeconds(600)), any());
        verify(employeeRepository).findReapableBySyncStatus(eq(SyncStatus.SYNC_FAILED),
                eq(NOW.minusSeconds(600)), any());
    }

    @Test
    void recentRowsExcludedByQueryAreNotRequeued() {
        // Repos return empty (simulating threshold filter excluding recent rows) -> nothing requeued.
        var result = service.claim(cfg);
        assertThat(result.toPublish()).isEmpty();
        assertThat(result.requeued()).isZero();
    }

    @Test
    void stuckSyncingEmployeeIsReclaimedAndRequeued() {
        var emp = employee(EmployeeStatus.ACTIVE, SyncStatus.SYNCING, 1, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.SYNCING), any(), any())).thenReturn(List.of(emp));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).hasSize(1);
        assertThat(result.syncingStuck()).isEqualTo(1);
        verify(employeeRepository).save(emp);
    }

    @Test
    void recoverableFailedEmployeeIsRequeued() {
        var emp = employee(EmployeeStatus.ACTIVE, SyncStatus.SYNC_FAILED, 2, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.SYNC_FAILED), any(), any())).thenReturn(List.of(emp));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).hasSize(1);
        assertThat(result.failedRetriable()).isEqualTo(1);
    }

    @Test
    void failedEmployeeOverMaxRequeuesIsSkipped() {
        var emp = employee(EmployeeStatus.ACTIVE, SyncStatus.SYNC_FAILED, 5, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.SYNC_FAILED), any(), any())).thenReturn(List.of(emp));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).isEmpty();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(emp.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void inactiveEmployeeIsNotRequeued() {
        var emp = employee(EmployeeStatus.INACTIVE, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any())).thenReturn(List.of(emp));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).isEmpty();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(emp.getSyncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void expiredGuestIsNotRequeued() {
        var guest = guest(GuestStatus.COMPLETED, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/g.jpg", "12345678901",
                "Visitante", NOW.minusSeconds(3600), null);
        when(guestRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any())).thenReturn(List.of(guest));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).isEmpty();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void validCompletedGuestIsRequeued() {
        var guest = guest(GuestStatus.COMPLETED, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/g.jpg", "12345678901",
                "Visitante", NOW.plusSeconds(3600), null);
        when(guestRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any())).thenReturn(List.of(guest));

        var result = service.claim(cfg);

        assertThat(result.toPublish()).containsExactly(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
    }

    @Test
    void batchSizeIsPassedToTheQuery() {
        cfg.setBatchSize(25);

        service.claim(cfg);

        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(employeeRepository, atLeastOnce())
                .findReapableBySyncStatus(any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void concurrentRunsDoNotRequeueTheSamePersonTwice() {
        var emp = employee(EmployeeStatus.ACTIVE, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/a.jpg", null, "12345678901", "Fulano");
        // First pass sees the candidate; once claimed (SYNCING + fresh updated_at) the SKIP-LOCKED /
        // threshold query no longer returns it — simulated here by returning empty afterwards.
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any()))
                .thenReturn(List.of(emp))
                .thenReturn(List.of());

        var first = service.claim(cfg);
        var second = service.claim(cfg);

        assertThat(first.toPublish()).hasSize(1);
        assertThat(second.toPublish()).isEmpty();
        verify(employeeRepository, times(1)).save(emp);
    }

    @Test
    void logsDoNotExposeCpfOrFullName() {
        var listAppender = attachAppender(IntelbrasSyncReaperService.class);
        var requeued = employee(EmployeeStatus.ACTIVE, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/a.jpg", null,
                "12345678901", "Joao Silva Pereira");
        var skipped = employee(EmployeeStatus.INACTIVE, SyncStatus.PENDING_SYNC, 0, "/uploads/faces/b.jpg", null,
                "98765432100", "Maria Souza Lima");
        when(employeeRepository.findReapableBySyncStatus(eq(SyncStatus.PENDING_SYNC), any(), any()))
                .thenReturn(List.of(requeued, skipped));

        service.claim(cfg);

        var messages = listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains("12345678901") || m.contains("98765432100")
                || m.contains("Joao Silva Pereira") || m.contains("Maria Souza Lima"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }

    private static Employee employee(EmployeeStatus status, SyncStatus sync, int attempts, String face,
                                     String card, String cpf, String name) {
        var emp = instantiate(Employee.class);
        ReflectionTestUtils.setField(emp, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(emp, "status", status);
        ReflectionTestUtils.setField(emp, "syncStatus", sync);
        ReflectionTestUtils.setField(emp, "syncAttempts", attempts);
        ReflectionTestUtils.setField(emp, "facePhotoUrl", face);
        ReflectionTestUtils.setField(emp, "intelbrasCardNo", card);
        ReflectionTestUtils.setField(emp, "cpf", cpf);
        ReflectionTestUtils.setField(emp, "fullName", name);
        return emp;
    }

    private static Guest guest(GuestStatus status, SyncStatus sync, int attempts, String face, String cpf,
                               String name, Instant visitEnd, LocalDate invitedDay) {
        var guest = instantiate(Guest.class);
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(guest, "status", status);
        ReflectionTestUtils.setField(guest, "syncStatus", sync);
        ReflectionTestUtils.setField(guest, "syncAttempts", attempts);
        ReflectionTestUtils.setField(guest, "facePhotoUrl", face);
        ReflectionTestUtils.setField(guest, "cpf", cpf);
        ReflectionTestUtils.setField(guest, "fullName", name);
        ReflectionTestUtils.setField(guest, "visitEnd", visitEnd);
        ReflectionTestUtils.setField(guest, "invitedDay", invitedDay);
        return guest;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
