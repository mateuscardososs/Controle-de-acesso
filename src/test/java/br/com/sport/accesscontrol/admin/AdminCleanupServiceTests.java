package br.com.sport.accesscontrol.admin;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCleanupServiceTests {

    @Test
    void adminCleansAccessEventsWithCorrectConfirmationAndAudits() {
        var accessEvents = mock(AccessEventRepository.class);
        var audit = mock(AuditService.class);
        when(accessEvents.count()).thenReturn(12L);
        var service = new AdminCleanupService(accessEvents, mock(EmployeeRepository.class), mock(UserRepository.class), audit);

        var response = service.accessEvents(new AdminCleanupDtos.CleanupRequest("LIMPAR_EVENTOS"));

        assertThat(response.removedCount()).isEqualTo(12);
        verify(accessEvents).deleteAllInBatch();
        verify(audit).record(eq("ADMIN_CLEANUP_ACCESS_EVENTS"), eq("AccessEvent"), eq(null), any(), eq(Map.of()), eq(Map.of()));
    }

    @Test
    void wrongConfirmationDoesNotCleanAccessEvents() {
        var accessEvents = mock(AccessEventRepository.class);
        var service = new AdminCleanupService(accessEvents, mock(EmployeeRepository.class), mock(UserRepository.class), mock(AuditService.class));

        assertThatThrownBy(() -> service.accessEvents(new AdminCleanupDtos.CleanupRequest("LIMPAR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Confirmação inválida.");
        verify(accessEvents, never()).deleteAllInBatch();
    }

    @Test
    void employeeCleanupPreservesLoggedAdminAndAdminUsers() {
        var currentAdmin = user(UserRole.ADMIN);
        var hrUser = user(UserRole.HR);
        var viewerUser = user(UserRole.SECURITY_VIEWER);
        var currentAdminEmployee = employee(UserRole.ADMIN, currentAdmin.getId());
        var adminEmployee = employee(UserRole.ADMIN, null);
        var hrEmployee = employee(UserRole.HR, hrUser.getId());
        var viewerEmployee = employee(UserRole.SECURITY_VIEWER, viewerUser.getId());
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var audit = mock(AuditService.class);
        when(employeeRepository.findAll()).thenReturn(List.of(currentAdminEmployee, adminEmployee, hrEmployee, viewerEmployee));
        when(userRepository.findAllById(any())).thenReturn(List.of(currentAdmin, hrUser, viewerUser));
        var service = new AdminCleanupService(mock(AccessEventRepository.class), employeeRepository, userRepository, audit);

        var response = service.employees(
                new AdminCleanupDtos.CleanupRequest("LIMPAR_COLABORADORES"),
                new UsernamePasswordAuthenticationToken(new UserPrincipal(currentAdmin), null, List.of())
        );

        assertThat(response.removedCount()).isEqualTo(2);
        verify(employeeRepository).deleteAllInBatch(argThat((List<Employee> employees) ->
                employees.containsAll(List.of(hrEmployee, viewerEmployee))
                        && !employees.contains(currentAdminEmployee)
                        && !employees.contains(adminEmployee)
        ));
        verify(userRepository).deleteAllInBatch(argThat((List<User> users) ->
                users.containsAll(List.of(hrUser, viewerUser)) && !users.contains(currentAdmin)
        ));
        verify(audit).record(eq("ADMIN_CLEANUP_EMPLOYEES"), eq("Employee"), eq(null), any(), eq(Map.of()), eq(Map.of()));
    }

    @Test
    void wrongConfirmationDoesNotCleanEmployees() {
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var service = new AdminCleanupService(mock(AccessEventRepository.class), employeeRepository, userRepository, mock(AuditService.class));

        assertThatThrownBy(() -> service.employees(new AdminCleanupDtos.CleanupRequest("LIMPAR"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Confirmação inválida.");
        verify(employeeRepository, never()).deleteAllInBatch(any());
        verify(userRepository, never()).deleteAllInBatch(any());
    }

    private Employee employee(UserRole role, UUID userId) {
        var employee = new Employee("Colaborador", validCpf(role), "colaborador@empresa.local", null,
                null, null, role, EmployeeStatus.ACTIVE, null, null);
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        employee.setUserId(userId);
        return employee;
    }

    private User user(UserRole role) {
        var user = new User(role.name(), role.name().toLowerCase() + "." + UUID.randomUUID() + "@empresa.local",
                "hash", role, true);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private String validCpf(UserRole role) {
        return switch (role) {
            case ADMIN -> "52998224725";
            case HR -> "11144477735";
            case SECURITY_VIEWER -> "39053344705";
        };
    }
}
