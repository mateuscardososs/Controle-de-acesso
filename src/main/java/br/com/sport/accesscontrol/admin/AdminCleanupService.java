package br.com.sport.accesscontrol.admin;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.permissions.AccessPermissionRepository;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminCleanupService {

    static final String ACCESS_EVENTS_CONFIRMATION = "LIMPAR_EVENTOS";
    static final String EMPLOYEES_CONFIRMATION = "LIMPAR_COLABORADORES";

    private final AccessEventRepository accessEventRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AccessPermissionRepository accessPermissionRepository;
    private final AuditService auditService;

    public AdminCleanupService(AccessEventRepository accessEventRepository, EmployeeRepository employeeRepository,
                               UserRepository userRepository, AccessPermissionRepository accessPermissionRepository,
                               AuditService auditService) {
        this.accessEventRepository = accessEventRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.accessPermissionRepository = accessPermissionRepository;
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
        var currentAdmin = requireAdmin(authentication);
        requireConfirmation(request, EMPLOYEES_CONFIRMATION);
        var employees = employeeRepository.findAll();
        var employeeIds = employees.stream()
                .map(Employee::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var usersById = userRepository.findAllById(employees.stream()
                .map(Employee::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        var usersToDelete = employees.stream()
                .map(Employee::getUserId)
                .filter(Objects::nonNull)
                .map(usersById::get)
                .filter(Objects::nonNull)
                .filter(user -> user.getRole() != UserRole.ADMIN)
                .filter(user -> !user.getId().equals(currentAdmin.getId()))
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();

        var removedPermissions = employeeIds.isEmpty()
                ? 0
                : accessPermissionRepository.deleteByPersonTypeAndPersonIdIn(PersonType.EMPLOYEE, employeeIds);
        employeeRepository.deleteAllInBatch(employees);
        userRepository.deleteAllInBatch(usersToDelete);

        var details = new LinkedHashMap<String, Object>();
        details.put("removedEmployees", employees.size());
        details.put("removedUsers", usersToDelete.size());
        details.put("removedAccessPermissions", removedPermissions);
        details.put("preservedAdminUsers", usersById.values().stream()
                .filter(user -> user.getRole() == UserRole.ADMIN)
                .count());
        details.put("preservedCurrentAdminUserId", currentAdmin.getId());
        details.put("cleanedAt", Instant.now().toString());
        auditService.record("ADMIN_CLEANUP_EMPLOYEES", "Employee", null, details, Map.of(), Map.of());

        return new AdminCleanupDtos.CleanupResponse(
                employees.size(),
                employees.size() + " colaborador(es) e " + usersToDelete.size() + " usuário(s) vinculado(s) removido(s)."
        );
    }

    private User requireAdmin(Authentication authentication) {
        var userId = currentUserId(authentication);
        if (userId == null) {
            throw new AccessDeniedException("Apenas administradores podem limpar colaboradores.");
        }
        var currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Apenas administradores podem limpar colaboradores."));
        if (currentUser.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Apenas administradores podem limpar colaboradores.");
        }
        return currentUser;
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
