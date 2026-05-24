package br.com.sport.accesscontrol.admin;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AdminCleanupService {

    static final String ACCESS_EVENTS_CONFIRMATION = "LIMPAR_EVENTOS";
    static final String EMPLOYEES_CONFIRMATION = "LIMPAR_COLABORADORES";

    private final AccessEventRepository accessEventRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AdminCleanupService(AccessEventRepository accessEventRepository, EmployeeRepository employeeRepository,
                               UserRepository userRepository, AuditService auditService) {
        this.accessEventRepository = accessEventRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AdminCleanupDtos.CleanupResponse accessEvents(AdminCleanupDtos.CleanupRequest request) {
        requireConfirmation(request, ACCESS_EVENTS_CONFIRMATION);
        var count = accessEventRepository.count();
        accessEventRepository.deleteAllInBatch();
        auditService.record("ADMIN_CLEANUP_ACCESS_EVENTS", "AccessEvent", null,
                Map.of("removedCount", count, "cleanedAt", Instant.now().toString()),
                Map.of(),
                Map.of());
        return new AdminCleanupDtos.CleanupResponse(count, count + " evento(s) removido(s).");
    }

    @Transactional
    public AdminCleanupDtos.CleanupResponse employees(AdminCleanupDtos.CleanupRequest request, Authentication authentication) {
        requireConfirmation(request, EMPLOYEES_CONFIRMATION);
        var currentUserId = currentUserId(authentication);
        var employees = employeeRepository.findAll();
        var usersById = userRepository.findAllById(employees.stream()
                .map(Employee::getUserId)
                .filter(Objects::nonNull)
                .toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        var employeesToDelete = employees.stream()
                .filter(employee -> canDeleteEmployee(employee, usersById.get(employee.getUserId()), currentUserId))
                .toList();
        var usersToDelete = employeesToDelete.stream()
                .map(Employee::getUserId)
                .filter(Objects::nonNull)
                .map(usersById::get)
                .filter(Objects::nonNull)
                .filter(user -> user.getRole() != UserRole.ADMIN)
                .filter(user -> !user.getId().equals(currentUserId))
                .toList();

        employeeRepository.deleteAllInBatch(employeesToDelete);
        userRepository.deleteAllInBatch(usersToDelete);

        var details = new LinkedHashMap<String, Object>();
        details.put("removedEmployees", employeesToDelete.size());
        details.put("removedUsers", usersToDelete.size());
        details.put("preservedAdmins", employees.size() - employeesToDelete.size());
        details.put("cleanedAt", Instant.now().toString());
        auditService.record("ADMIN_CLEANUP_EMPLOYEES", "Employee", null, details, Map.of(), Map.of());

        return new AdminCleanupDtos.CleanupResponse(
                employeesToDelete.size(),
                employeesToDelete.size() + " colaborador(es) removido(s)."
        );
    }

    private boolean canDeleteEmployee(Employee employee, User linkedUser, UUID currentUserId) {
        if (employee.getRole() == UserRole.ADMIN) {
            return false;
        }
        if (employee.getUserId() != null && employee.getUserId().equals(currentUserId)) {
            return false;
        }
        return linkedUser == null || linkedUser.getRole() != UserRole.ADMIN;
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.id();
        }
        return null;
    }

    private void requireConfirmation(AdminCleanupDtos.CleanupRequest request, String expected) {
        if (request == null || !expected.equals(request.confirmation())) {
            throw new IllegalArgumentException("Confirmação inválida.");
        }
    }
}
