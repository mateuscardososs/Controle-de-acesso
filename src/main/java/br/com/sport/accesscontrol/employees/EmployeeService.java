package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.common.events.EmployeeCreatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeDeactivatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeUpdatedEvent;
import br.com.sport.accesscontrol.integration.sync.EmployeeReadyForSyncEvent;
import br.com.sport.accesscontrol.guests.FaceStorageService;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FaceStorageService faceStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private static final ZoneId ACCESS_ZONE = ZoneId.of("America/Recife");

    public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder, FaceStorageService faceStorageService,
                           ApplicationEventPublisher eventPublisher,
                           AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.faceStorageService = faceStorageService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public EmployeeResponse create(EmployeeRequest request) {
        return create(request, null);
    }

    @Transactional
    public EmployeeResponse create(EmployeeRequest request, MultipartFile facePhoto) {
        validateCreateRequest(request);
        var email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        var validFrom = monthlyValidFrom(request.accessValidFrom());
        var validUntil = monthlyValidUntil(request.accessValidUntil());
        var user = userRepository.save(new User(
                request.fullName().trim(),
                email,
                passwordEncoder.encode(request.password()),
                request.role(),
                true
        ));
        var employee = new Employee(
                request.fullName().trim(),
                normalizeCpf(request.cpf()),
                email,
                normalize(request.phone()),
                normalize(request.registrationNumber()),
                normalizeCardNo(request.cardNo()),
                normalize(request.facePhotoUrl()),
                request.role(),
                request.status(),
                validFrom,
                validUntil
        );
        employee.setUserId(user.getId());
        var saved = employeeRepository.save(employee);
        if (facePhoto != null && !facePhoto.isEmpty()) {
            saved.setFacePhotoUrl(faceStorageService.store(facePhoto, saved.getId()));
            saved = employeeRepository.save(saved);
        }
        auditService.record("EMPLOYEE_CREATED", "Employee", saved.getId(), Map.of("cpf", saved.getCpf(), "role", saved.getRole()),
                Map.of(), employeeSnapshot(saved));
        eventPublisher.publishEvent(new EmployeeCreatedEvent(saved.getId()));
        return EmployeeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> findAll() {
        return employeeRepository.findAll().stream().map(EmployeeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(UUID id) {
        return EmployeeResponse.from(getEmployee(id));
    }

    @Transactional
    public EmployeeResponse update(UUID id, EmployeeRequest request) {
        var employee = getEmployee(id);
        var oldData = employeeSnapshot(employee);
        var email = hasText(request.email()) ? normalizeEmail(request.email()) : null;
        updateLinkedUser(employee, request, email);
        employee.setFullName(request.fullName().trim());
        employee.setCpf(normalizeCpf(request.cpf()));
        employee.setEmail(email);
        employee.setPhone(normalize(request.phone()));
        employee.setRegistrationNumber(normalize(request.registrationNumber()));
        employee.setCardNo(normalizeCardNo(request.cardNo()));
        employee.setFacePhotoUrl(normalize(request.facePhotoUrl()));
        if (request.role() != null) {
            employee.setRole(request.role());
        }
        employee.setStatus(request.status() == null ? employee.getStatus() : request.status());
        employee.setAccessValidFrom(monthlyValidFrom(request.accessValidFrom()));
        employee.setAccessValidUntil(monthlyValidUntil(request.accessValidUntil()));
        employee.markPendingSync();
        auditService.record("EMPLOYEE_UPDATED", "Employee", employee.getId(), Map.of("cpf", employee.getCpf(), "role", employee.getRole()),
                oldData, employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeUpdatedEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse requestSync(UUID id) {
        var employee = getEmployee(id);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("Colaborador precisa estar ativo para sincronizar.");
        }
        if (!hasText(employee.getFacePhotoUrl()) && !hasText(employee.getCardNo())) {
            throw new IllegalArgumentException("Informe foto facial ou tag/cartão antes da sincronização.");
        }
        var oldData = employeeSnapshot(employee);
        employee.markPendingSync();
        employeeRepository.save(employee);
        auditService.record("EMPLOYEE_SYNC_REQUESTED", "Employee", employee.getId(),
                Map.of(
                        "validFrom", employee.getAccessValidFrom() == null ? "" : employee.getAccessValidFrom(),
                        "validUntil", employee.getAccessValidUntil() == null ? "" : employee.getAccessValidUntil(),
                        "cardNo", employee.getCardNo() == null ? "" : employee.getCardNo()
                ),
                oldData,
                employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeReadyForSyncEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse deactivate(UUID id) {
        var employee = getEmployee(id);
        var oldData = employeeSnapshot(employee);
        employee.setStatus(EmployeeStatus.INACTIVE);
        if (employee.getUserId() != null) {
            userRepository.findById(employee.getUserId()).ifPresent(user -> user.setActive(false));
        }
        auditService.record("EMPLOYEE_DEACTIVATED", "Employee", employee.getId(), Map.of("cpf", employee.getCpf()),
                oldData, employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeDeactivatedEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    private Employee getEmployee(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private void validateCreateRequest(EmployeeRequest request) {
        if (!hasText(request.email())) {
            throw new IllegalArgumentException("Employee email is required.");
        }
        if (!hasText(request.password())) {
            throw new IllegalArgumentException("Employee password is required.");
        }
        if (request.role() == null) {
            throw new IllegalArgumentException("Employee role is required.");
        }
    }

    private void updateLinkedUser(Employee employee, EmployeeRequest request, String email) {
        if (employee.getUserId() == null && !hasText(request.password()) && request.role() == null) {
            return;
        }
        if (!hasText(email)) {
            throw new IllegalArgumentException("Employee email is required to manage admin access.");
        }
        var user = employee.getUserId() == null
                ? userRepository.findByEmailIgnoreCase(email).orElse(null)
                : userRepository.findById(employee.getUserId()).orElse(null);
        if (user == null) {
            if (!hasText(request.password())) {
                throw new IllegalArgumentException("Employee password is required to create admin access.");
            }
            if (request.role() == null) {
                throw new IllegalArgumentException("Employee role is required to create admin access.");
            }
            user = userRepository.save(new User(
                    request.fullName().trim(),
                    email,
                    passwordEncoder.encode(request.password()),
                    request.role(),
                    true
            ));
            employee.setUserId(user.getId());
            return;
        }
        var currentUser = user;
        userRepository.findByEmailIgnoreCase(email)
                .filter(existing -> !existing.getId().equals(currentUser.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Email already registered");
                });
        user.setName(request.fullName().trim());
        user.setEmail(email);
        if (hasText(request.password())) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        var desiredStatus = request.status() == null ? employee.getStatus() : request.status();
        user.setActive(desiredStatus != EmployeeStatus.INACTIVE);
        employee.setUserId(user.getId());
    }

    private Map<String, Object> employeeSnapshot(Employee employee) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", employee.getId());
        snapshot.put("fullName", employee.getFullName());
        snapshot.put("cpf", employee.getCpf());
        snapshot.put("email", employee.getEmail() == null ? "" : employee.getEmail());
        snapshot.put("cardNo", employee.getCardNo() == null ? "" : employee.getCardNo());
        snapshot.put("role", employee.getRole() == null ? "" : employee.getRole());
        snapshot.put("userId", employee.getUserId() == null ? "" : employee.getUserId());
        snapshot.put("status", employee.getStatus());
        snapshot.put("accessValidFrom", employee.getAccessValidFrom() == null ? "" : employee.getAccessValidFrom());
        snapshot.put("accessValidUntil", employee.getAccessValidUntil() == null ? "" : employee.getAccessValidUntil());
        snapshot.put("syncStatus", employee.getSyncStatus());
        return snapshot;
    }

    private Instant monthlyValidFrom(Instant requested) {
        if (requested != null) {
            return requested;
        }
        var today = LocalDate.now(ACCESS_ZONE);
        return today.withDayOfMonth(1).atStartOfDay(ACCESS_ZONE).toInstant();
    }

    private Instant monthlyValidUntil(Instant requested) {
        if (requested != null) {
            return requested;
        }
        var today = LocalDate.now(ACCESS_ZONE);
        return today.withDayOfMonth(today.lengthOfMonth()).atTime(LocalTime.of(23, 59, 59)).atZone(ACCESS_ZONE).toInstant();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCpf(String cpf) {
        return cpf == null ? "" : cpf.replaceAll("\\D", "");
    }

    private String normalizeCardNo(String cardNo) {
        var normalized = normalize(cardNo);
        return normalized == null ? null : normalized.replaceAll("\\D", "");
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
