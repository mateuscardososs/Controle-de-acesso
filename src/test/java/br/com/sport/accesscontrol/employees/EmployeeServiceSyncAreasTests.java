package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.areas.LoungeAreaResolver;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.guests.FaceStorageService;
import br.com.sport.accesscontrol.integration.sync.EmployeeReadyForSyncEvent;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeServiceSyncAreasTests {

    @Test
    void manualSyncRebuildsFullAccessAreasBeforePublishingEvent() {
        var employee = new Employee("Colaborador", "12345678901", "colaborador@empresa.local", null,
                null, "445566", null, UserRole.ADMIN, EmployeeStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        var portaria = area("Portaria");
        var front1 = area("Front 1");
        var employeeRepository = mock(EmployeeRepository.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var loungeAreaResolver = mock(LoungeAreaResolver.class);
        when(employeeRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(employeeRepository.save(employee)).thenReturn(employee);
        when(loungeAreaResolver.resolveAllForEmployee()).thenReturn(new LinkedHashSet<>(java.util.List.of(portaria, front1)));
        var service = new EmployeeService(employeeRepository, mock(UserRepository.class), new BCryptPasswordEncoder(),
                mock(FaceStorageService.class), eventPublisher, mock(AuditService.class), loungeAreaResolver);

        var response = service.requestSync(employee.getId());

        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(response.allowedAreaIds()).containsExactly(portaria.getId(), front1.getId());
        verify(eventPublisher).publishEvent(new EmployeeReadyForSyncEvent(employee.getId()));
    }

    private Area area(String name) {
        var area = new Area(name, name, true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }
}
