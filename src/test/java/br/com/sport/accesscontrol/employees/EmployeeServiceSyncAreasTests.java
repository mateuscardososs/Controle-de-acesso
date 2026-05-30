package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.areas.LoungeAreaResolver;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.guests.FaceStorageService;
import br.com.sport.accesscontrol.integration.sync.EmployeeReadyForSyncEvent;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeServiceSyncAreasTests {

    @Test
    void createAppliesDefaultFortyFiveDayValidityWhenDatesAreOmitted() {
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmailIgnoreCase("colaborador@empresa.local")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            if (employee.getId() == null) {
                ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
            }
            return employee;
        });
        var service = new EmployeeService(employeeRepository, userRepository, new BCryptPasswordEncoder(),
                mock(FaceStorageService.class), mock(ApplicationEventPublisher.class), mock(AuditService.class));

        var before = Instant.now();
        var response = service.create(new EmployeeRequest(
                "Colaborador",
                "529.982.247-25",
                "Colaborador@Empresa.Local",
                null,
                null,
                "445566",
                null,
                "Senha@123",
                UserRole.HR,
                EmployeeStatus.ACTIVE,
                null,
                null
        ));
        var after = Instant.now();

        assertThat(response.accessValidFrom()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(Duration.between(response.accessValidFrom(), response.accessValidUntil()))
                .isEqualTo(Duration.ofDays(45));
    }

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
