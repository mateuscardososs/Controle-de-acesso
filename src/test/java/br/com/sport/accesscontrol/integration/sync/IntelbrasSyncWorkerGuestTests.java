package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.Guest;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.mail.MailDeliveryResult;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelbrasSyncWorkerGuestTests {

    private GuestRepository guestRepository;
    private AccessControlProvider provider;
    private IntelbrasSyncWorker worker;

    @BeforeEach
    void setUp() {
        guestRepository = mock(GuestRepository.class);
        provider = mock(AccessControlProvider.class);
        var employeeRepository = mock(EmployeeRepository.class);
        var deviceRepository = mock(DeviceRepository.class);
        var auditService = mock(AuditService.class);
        var eventPublisher = mock(IntegrationEventPublisher.class);
        var realtimePublisher = mock(IntegrationSyncRealtimePublisher.class);
        var systemRealtimePublisher = mock(RealtimePublisherService.class);
        var mailService = mock(MailService.class);
        when(mailService.sendGuestAccessApproved(any())).thenReturn(MailDeliveryResult.skipped("disabled"));
        when(deviceRepository.findAll()).thenReturn(List.of());

        worker = new IntelbrasSyncWorker(
                employeeRepository, guestRepository, deviceRepository,
                provider, auditService, eventPublisher,
                realtimePublisher, systemRealtimePublisher,
                mailService, new SimpleMeterRegistry(), 3
        );
    }

    @Test
    void syncSuccessMarksGuestSynced() {
        var guest = completedGuest(null);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(success());

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(guest.getLastSyncAt()).isNotNull();
        assertThat(guest.getLastSyncError()).isNull();
    }

    @Test
    void syncFailureMarksGuestSyncFailed() {
        var guest = completedGuest(null);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(failed("No configured Intelbras real devices found."));

        // attempt=1 < maxAttempts=3 → handleFailure schedules retry (no throw); finishGuest already set SYNC_FAILED
        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        assertThat(guest.getLastSyncError()).contains("No configured");
    }

    @Test
    void guestWithInvitedDayUsesInvitedDayBasedValidity() {
        var invitedDay = LocalDate.of(2025, 6, 21);
        var guest = completedGuest(invitedDay);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        var zone = ZoneId.of("America/Recife");
        var expectedFrom = invitedDay.atTime(LocalTime.of(15, 0)).atZone(zone).toInstant();
        var expectedUntil = invitedDay.plusDays(1).atTime(LocalTime.of(4, 0)).atZone(zone).toInstant();

        when(provider.syncPerson(any())).thenAnswer(inv -> {
            var person = inv.getArgument(0, br.com.sport.accesscontrol.integration.provider.ProviderPerson.class);
            assertThat(person.validFrom()).isEqualTo(expectedFrom);
            assertThat(person.validUntil()).isEqualTo(expectedUntil);
            return success();
        });

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void guestWithoutInvitedDayUsesVisitStartEnd() {
        var visitStart = Instant.parse("2025-06-21T18:00:00Z");
        var visitEnd = Instant.parse("2025-06-22T07:00:00Z");
        var guest = completedGuestWithDates(null, visitStart, visitEnd);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        when(provider.syncPerson(any())).thenAnswer(inv -> {
            var person = inv.getArgument(0, br.com.sport.accesscontrol.integration.provider.ProviderPerson.class);
            assertThat(person.validFrom()).isEqualTo(visitStart);
            assertThat(person.validUntil()).isEqualTo(visitEnd);
            return success();
        });

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
    }

    @Test
    void guestSyncUsesOnlyActiveAllowedAreas() {
        var portaria = area("Portaria", true);
        var legacyFront3 = area("Front 3", false);
        var guest = completedGuest(LocalDate.of(2025, 6, 21));
        guest.replaceAllowedAreas(new LinkedHashSet<>(List.of(portaria, legacyFront3)));
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        when(provider.syncPerson(any())).thenAnswer(inv -> {
            var person = inv.getArgument(0, br.com.sport.accesscontrol.integration.provider.ProviderPerson.class);
            assertThat(person.allowedAreaIds()).containsExactly(portaria.getId());
            assertThat(person.allowedAreaIds()).doesNotContain(legacyFront3.getId());
            return success();
        });

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
    }

    @Test
    void partialSuccessMarksGuestSyncedWithWarnings() {
        var guest = completedGuest(null);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(partialSuccess());

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED_WITH_WARNINGS);
        assertThat(guest.getLastSyncError()).contains("1 de 2");
        assertThat(guest.getSyncTargetCount()).isEqualTo(2);
        assertThat(guest.getSyncSuccessCount()).isEqualTo(1);
        assertThat(guest.getSyncFailedCount()).isEqualTo(1);
    }

    @Test
    void allowedAreaIdsArePassedToProviderIncludingOnlyActiveAreas() {
        var portaria = area("Portaria", true);
        var front1 = area("Front 1", true);
        var inactive = area("Antigo", false);
        var guest = completedGuest(LocalDate.of(2025, 6, 21));
        guest.replaceAllowedAreas(new LinkedHashSet<>(List.of(portaria, front1, inactive)));
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        when(provider.syncPerson(any())).thenAnswer(inv -> {
            var person = inv.getArgument(0, br.com.sport.accesscontrol.integration.provider.ProviderPerson.class);
            assertThat(person.allowedAreaIds()).containsExactlyInAnyOrder(portaria.getId(), front1.getId());
            assertThat(person.allowedAreaIds()).doesNotContain(inactive.getId());
            return success();
        });

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));
        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void workerDoesNotRetryWhenGuestAlreadySyncedOnSecondAttempt() {
        var guest = completedGuest(null);
        guest.markSynced();
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 2));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void syncFailedStatusIsSetWhenGuestHasNoAllowedAreas() {
        var guest = completedGuest(null);
        guest.replaceAllowedAreas(new LinkedHashSet<>());
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(failed("Visitante sem áreas permitidas"));

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        assertThat(guest.getLastSyncError()).contains("Visitante sem áreas permitidas");
    }

    @Test
    void guestCannotBeMarkedSyncedWhenProviderReportsZeroSuccesses() {
        var guest = completedGuest(null);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(
                ProviderSyncStatus.FAILED,
                "Falha: 0 de 5 controladoras.",
                Duration.ofMillis(10),
                5,
                0,
                5,
                0
        ));

        worker.process(new IntelbrasSyncMessage(PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        assertThat(guest.getSyncTargetCount()).isEqualTo(5);
        assertThat(guest.getSyncSuccessCount()).isZero();
        assertThat(guest.getLastSyncError()).contains("0 de 5");
    }

    private Guest completedGuest(LocalDate invitedDay) {
        var start = Instant.now().minusSeconds(3600);
        var end = Instant.now().plusSeconds(3600);
        return completedGuestWithDates(invitedDay, start, end);
    }

    private Guest completedGuestWithDates(LocalDate invitedDay, Instant visitStart, Instant visitEnd) {
        var guest = new Guest("Visitante Teste", "12345678901", null, "11999999999",
                null, "Evento", "Host", visitStart, visitEnd, invitedDay, "Front 1");
        guest.completeRegistration(null, null, "/uploads/faces/fake.jpg");
        try {
            var idField = Guest.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(guest, java.util.UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return guest;
    }

    private Area area(String name, boolean active) {
        var area = new Area(name, name, active);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }

    private ProviderSyncResult success() {
        return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "ok", Duration.ofMillis(10));
    }

    private ProviderSyncResult failed(String message) {
        return new ProviderSyncResult(ProviderSyncStatus.FAILED, message, Duration.ofMillis(10));
    }

    private ProviderSyncResult partialSuccess() {
        return new ProviderSyncResult(ProviderSyncStatus.PARTIAL_SUCCESS,
                "Parcial: 1 de 2 controladoras.", Duration.ofMillis(10), 2, 1, 1, 0);
    }
}
