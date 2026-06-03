package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelbrasSyncWorkerEmployeeTests {

    private EmployeeRepository employeeRepository;
    private AccessControlProvider provider;
    private IntelbrasSyncWorker worker;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        provider = mock(AccessControlProvider.class);
        worker = new IntelbrasSyncWorker(
                employeeRepository,
                mock(GuestRepository.class),
                mock(DeviceRepository.class),
                provider,
                mock(AuditService.class),
                mock(IntegrationEventPublisher.class),
                mock(IntegrationSyncRealtimePublisher.class),
                mock(RealtimePublisherService.class),
                mock(MailService.class),
                new SimpleMeterRegistry(),
                3
        );
    }

    @Test
    void employeeFullAccessPassesAllActiveAllowedAreasToProvider() {
        var areas = List.of(
                area("Portaria", true),
                area("Front 1", true),
                area("Front 2", true),
                area("Institucional 1", true),
                area("Institucional Vereadores", true),
                area("Inativa", false)
        );
        var employee = employee();
        employee.replaceAllowedAreas(new LinkedHashSet<>(areas));
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenAnswer(invocation -> {
            var person = invocation.getArgument(0, ProviderPerson.class);
            assertThat(person.allowedAreaIds()).containsExactlyInAnyOrderElementsOf(
                    areas.stream().filter(Area::isActive).map(Area::getId).toList()
            );
            assertThat(person.allowedAreaIds()).doesNotContain(areas.getLast().getId());
            assertThat(person.validFrom()).isEqualTo(employee.getAccessValidFrom());
            assertThat(person.validUntil()).isEqualTo(employee.getAccessValidUntil());
            return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Sincronizado em 10 de 10 controladoras.",
                    Duration.ofMillis(10), 10, 10, 0, 0);
        });

        worker.process(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(employee.getSyncTargetCount()).isEqualTo(10);
        assertThat(employee.getSyncSuccessCount()).isEqualTo(10);
        verify(employeeRepository).findByIdWithAllowedAreas(employee.getId());
        verify(provider).syncPerson(any(ProviderPerson.class));
    }

    @Test
    void employeePartialResultIsNotMarkedAsFullSynced() {
        var employee = employee();
        employee.replaceAllowedAreas(new LinkedHashSet<>(List.of(area("Portaria", true))));
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(
                ProviderSyncStatus.PARTIAL_SUCCESS,
                "Parcial: 1 de 10 controladoras.",
                Duration.ofMillis(10),
                10,
                1,
                9,
                0
        ));

        worker.process(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNCED_WITH_WARNINGS);
        assertThat(employee.getSyncSuccessCount()).isEqualTo(1);
        assertThat(employee.getSyncTargetCount()).isEqualTo(10);
        assertThat(employee.getLastSyncError()).contains("1 de 10");
    }

    @Test
    void zeroConfirmedOnlyUnverifiedIsSyncFailedNotWarnings() {
        // 0 confirmadas, 0 falhas explícitas, apenas "não verificadas" (sessão RPC2 indisponível):
        // deve ser SYNC_FAILED — nunca SYNCED_WITH_WARNINGS (warnings só com >=1 confirmada).
        var employee = employee();
        employee.replaceAllowedAreas(new LinkedHashSet<>(List.of(area("Portaria", true))));
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(
                ProviderSyncStatus.FAILED,
                "Necessita verificação: 0 de 5 controladora(s) confirmada(s).",
                Duration.ofMillis(10),
                5, 0, 0, 5));

        worker.process(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        assertThat(employee.getSyncSuccessCount()).isZero();
    }

    @Test
    void employeeFinalStatusIsDerivedFromCountsEvenWhenProviderStatusIsFailed() {
        var employee = employee();
        employee.replaceAllowedAreas(new LinkedHashSet<>(List.of(area("Portaria", true))));
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(
                ProviderSyncStatus.FAILED,
                "CardNo rejeitado em uma controladora.",
                Duration.ofMillis(10),
                10,
                9,
                1,
                0
        ));

        worker.process(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNCED_WITH_WARNINGS);
        assertThat(employee.getSyncTargetCount()).isEqualTo(10);
        assertThat(employee.getSyncSuccessCount()).isEqualTo(9);
        assertThat(employee.getSyncFailedCount()).isEqualTo(1);
    }

    @Test
    void employeePartialProviderResultWithZeroSuccessesIsSyncFailed() {
        var employee = employee();
        employee.replaceAllowedAreas(new LinkedHashSet<>(List.of(area("Portaria", true))));
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(
                ProviderSyncStatus.PARTIAL_SUCCESS,
                "Falha em todas as controladoras.",
                Duration.ofMillis(10),
                10,
                0,
                10,
                0
        ));

        worker.process(new IntelbrasSyncMessage(PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        assertThat(employee.getSyncTargetCount()).isEqualTo(10);
        assertThat(employee.getSyncSuccessCount()).isZero();
        assertThat(employee.getSyncFailedCount()).isEqualTo(10);
    }

    private Employee employee() {
        var employee = new Employee("Colaborador Teste", "12345678901", "colaborador@example.com",
                null, "REG-1", "12345", "/uploads/faces/employee.jpg", null,
                EmployeeStatus.ACTIVE, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        return employee;
    }

    private Area area(String name, boolean active) {
        var area = new Area(name, name, active);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }
}
