package br.com.sport.accesscontrol.admin;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.permissions.AccessPermissionRepository;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        var service = new AdminCleanupService(accessEvents, mock(EmployeeRepository.class), mock(UserRepository.class),
                mock(AccessPermissionRepository.class), audit);

        var response = service.accessEvents(new AdminCleanupDtos.CleanupRequest("LIMPAR_EVENTOS"));

        assertThat(response.removedCount()).isEqualTo(12);
        verify(accessEvents).deleteAllInBatch();
        verify(audit).record(eq("ADMIN_CLEANUP_ACCESS_EVENTS"), eq("AccessEvent"), eq(null), any(), eq(Map.of()), eq(Map.of()));
    }

    @Test
    void wrongConfirmationDoesNotCleanAccessEvents() {
        var accessEvents = mock(AccessEventRepository.class);
        var service = new AdminCleanupService(accessEvents, mock(EmployeeRepository.class), mock(UserRepository.class),
                mock(AccessPermissionRepository.class), mock(AuditService.class));

        assertThatThrownBy(() -> service.accessEvents(new AdminCleanupDtos.CleanupRequest("LIMPAR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Confirmação inválida.");
        verify(accessEvents, never()).deleteAllInBatch();
    }

    @Test
    void employeeCleanupDeletesEmployeesPermissionsAndNonAdminLinkedUsers() {
        var currentAdmin = user(UserRole.ADMIN);
        var hrUser = user(UserRole.HR);
        var viewerUser = user(UserRole.SECURITY_VIEWER);
        var currentAdminEmployee = employee(UserRole.ADMIN, currentAdmin.getId());
        var adminEmployee = employee(UserRole.ADMIN, null);
        var hrEmployee = employee(UserRole.HR, hrUser.getId());
        var viewerEmployee = employee(UserRole.SECURITY_VIEWER, viewerUser.getId());
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var accessPermissionRepository = mock(AccessPermissionRepository.class);
        var audit = mock(AuditService.class);
        when(employeeRepository.findAll()).thenReturn(List.of(currentAdminEmployee, adminEmployee, hrEmployee, viewerEmployee));
        when(userRepository.findById(currentAdmin.getId())).thenReturn(Optional.of(currentAdmin));
        when(userRepository.findAllById(any())).thenReturn(List.of(currentAdmin, hrUser, viewerUser));
        when(accessPermissionRepository.deleteByPersonTypeAndPersonIdIn(eq(PersonType.EMPLOYEE), any())).thenReturn(4);
        var service = new AdminCleanupService(mock(AccessEventRepository.class), employeeRepository, userRepository,
                accessPermissionRepository, audit);

        var response = service.employees(
                new AdminCleanupDtos.CleanupRequest("LIMPAR_COLABORADORES"),
                new UsernamePasswordAuthenticationToken(new UserPrincipal(currentAdmin), null, List.of())
        );

        assertThat(response.removedCount()).isEqualTo(4);
        verify(employeeRepository).deleteAllInBatch(argThat((List<Employee> employees) ->
                employees.containsAll(List.of(currentAdminEmployee, adminEmployee, hrEmployee, viewerEmployee))
        ));
        verify(userRepository).deleteAllInBatch(argThat((List<User> users) ->
                users.containsAll(List.of(hrUser, viewerUser)) && !users.contains(currentAdmin)
        ));
        verify(accessPermissionRepository).deleteByPersonTypeAndPersonIdIn(eq(PersonType.EMPLOYEE), argThat(ids ->
                ids.containsAll(List.of(
                        currentAdminEmployee.getId(),
                        adminEmployee.getId(),
                        hrEmployee.getId(),
                        viewerEmployee.getId()
                ))
        ));
        verify(audit).record(eq("ADMIN_CLEANUP_EMPLOYEES"), eq("Employee"), eq(null), any(), eq(Map.of()), eq(Map.of()));
    }

    @Test
    void wrongConfirmationDoesNotCleanEmployees() {
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var accessPermissionRepository = mock(AccessPermissionRepository.class);
        var currentAdmin = user(UserRole.ADMIN);
        when(userRepository.findById(currentAdmin.getId())).thenReturn(Optional.of(currentAdmin));
        var service = new AdminCleanupService(mock(AccessEventRepository.class), employeeRepository, userRepository,
                accessPermissionRepository, mock(AuditService.class));

        assertThatThrownBy(() -> service.employees(new AdminCleanupDtos.CleanupRequest("LIMPAR"), authentication(currentAdmin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Confirmação inválida.");
        verify(employeeRepository, never()).deleteAllInBatch(any());
        verify(userRepository, never()).deleteAllInBatch(any());
        verify(accessPermissionRepository, never()).deleteByPersonTypeAndPersonIdIn(any(), any());
    }

    @Test
    void hrCannotCleanEmployees() {
        assertRoleCannotCleanEmployees(UserRole.HR);
    }

    @Test
    void securityViewerCannotCleanEmployees() {
        assertRoleCannotCleanEmployees(UserRole.SECURITY_VIEWER);
    }

    private void assertRoleCannotCleanEmployees(UserRole role) {
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var accessPermissionRepository = mock(AccessPermissionRepository.class);
        var user = user(role);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        var service = new AdminCleanupService(mock(AccessEventRepository.class), employeeRepository, userRepository,
                accessPermissionRepository, mock(AuditService.class));

        assertThatThrownBy(() -> service.employees(
                new AdminCleanupDtos.CleanupRequest("LIMPAR_COLABORADORES"),
                authentication(user)
        )).isInstanceOf(AccessDeniedException.class);
        verify(employeeRepository, never()).deleteAllInBatch(any());
        verify(userRepository, never()).deleteAllInBatch(any());
        verify(accessPermissionRepository, never()).deleteByPersonTypeAndPersonIdIn(any(), any());
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

    private UsernamePasswordAuthenticationToken authentication(User user) {
        return new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null, List.of());
    }

    private String validCpf(UserRole role) {
        return switch (role) {
            case ADMIN -> "52998224725";
            case HR -> "11144477735";
            case SECURITY_VIEWER -> "39053344705";
        };
    }
}
