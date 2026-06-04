package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.areas.LoungeAreaResolver;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.CpfValidator;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.common.events.EmployeeCreatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeDeactivatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeUpdatedEvent;
import br.com.sport.accesscontrol.integration.sync.EmployeeReadyForSyncEvent;
import br.com.sport.accesscontrol.guests.FaceStorageService;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FaceStorageService faceStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final LoungeAreaResolver loungeAreaResolver;
    private static final Duration DEFAULT_EMPLOYEE_VALIDITY = Duration.ofDays(45);

    /** Backward-compatible constructor (testes legados sem LoungeAreaResolver nem generator). */
    public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder, FaceStorageService faceStorageService,
                           ApplicationEventPublisher eventPublisher,
                           AuditService auditService) {
        this(employeeRepository, userRepository, passwordEncoder, faceStorageService, eventPublisher,
                auditService, null);
    }

    /** Backward-compatible constructor (testes com LoungeAreaResolver). */
    @org.springframework.beans.factory.annotation.Autowired
    public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder, FaceStorageService faceStorageService,
                           ApplicationEventPublisher eventPublisher,
                           AuditService auditService,
                           LoungeAreaResolver loungeAreaResolver) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.faceStorageService = faceStorageService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.loungeAreaResolver = loungeAreaResolver;
    }

    private void applyFullAccessAreas(Employee employee) {
        if (loungeAreaResolver == null) {
            return;
        }
        employee.replaceAllowedAreas(loungeAreaResolver.resolveAllForEmployee());
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
        var validity = resolveEmployeeValidity(request.accessValidFrom(), request.accessValidUntil());
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
                validity.validFrom(),
                validity.validUntil()
        );
        employee.setUserId(user.getId());
        var saved = employeeRepository.save(employee);
        if (facePhoto != null && !facePhoto.isEmpty()) {
            saved.setFacePhotoUrl(faceStorageService.store(facePhoto, saved.getId()));
            saved = employeeRepository.save(saved);
        }
        applyFullAccessAreas(saved);
        auditService.record("EMPLOYEE_CREATED", "Employee", saved.getId(), Map.of("cpf", saved.getCpf(), "role", saved.getRole()),
                Map.of(), employeeSnapshot(saved));
        eventPublisher.publishEvent(new EmployeeCreatedEvent(saved.getId()));
        return EmployeeResponse.from(saved);
    }

    @Transactional
    public PublicEmployeeRegistrationDtos.PublicEmployeeRegistrationResponse publicRegister(
            String fullName,
            String cpf,
            String phone,
            String email,
            MultipartFile facePhoto,
            String facePhotoBase64
    ) {
        log.info("PUBLIC_EMPLOYEE_REGISTRATION_RECEIVED cpf_present={} face_upload_present={} face_base64_present={}",
                hasText(cpf), facePhoto != null && !facePhoto.isEmpty(), hasText(facePhotoBase64));
        validatePublicRegistration(fullName, cpf, phone, email, facePhoto, facePhotoBase64);
        var normalizedCpf = normalizeCpf(cpf);
        if (employeeRepository.existsByCpf(normalizedCpf)) {
            throw new IllegalArgumentException("CPF já cadastrado.");
        }
        var validFrom = Instant.now();
        var validUntil = validFrom.plus(DEFAULT_EMPLOYEE_VALIDITY);
        var employee = new Employee(
                fullName.trim(),
                normalizedCpf,
                normalizeEmail(email),
                normalize(phone),
                null,
                null,
                null,
                null,
                EmployeeStatus.ACTIVE,
                validFrom,
                validUntil
        );
        var saved = employeeRepository.save(employee);
        var facePhotoUrl = storePublicEmployeeFace(facePhoto, facePhotoBase64, saved.getId());
        if (hasText(facePhotoUrl)) {
            saved.setFacePhotoUrl(facePhotoUrl);
            saved = employeeRepository.save(saved);
        }
        auditService.record("PUBLIC_EMPLOYEE_REGISTERED", "Employee", saved.getId(),
                Map.of(
                        "cpf", saved.getCpf(),
                        "validFrom", saved.getAccessValidFrom(),
                        "validUntil", saved.getAccessValidUntil()
                ),
                Map.of(),
                employeeSnapshot(saved));
        if (hasText(saved.getFacePhotoUrl()) || hasText(saved.getCardNo())) {
            triggerEmployeeAutoSyncAfterRegistration(saved, "PUBLIC_REGISTRATION");
        } else {
            log.info("EMPLOYEE_AUTO_SYNC_SKIPPED employee_id={} source=PUBLIC_REGISTRATION sync_status={} reason=credential_missing",
                    saved.getId(), saved.getSyncStatus());
        }
        log.info("PUBLIC_EMPLOYEE_REGISTRATION_CREATED employee_id={} cpf={} valid_until={} sync_status={}",
                saved.getId(), saved.getCpf(), saved.getAccessValidUntil(), saved.getSyncStatus());
        return PublicEmployeeRegistrationDtos.PublicEmployeeRegistrationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PublicEmployeeRegistrationDtos.CpfCheckResponse checkPublicCpf(String cpf) {
        var digits = CpfValidator.onlyDigits(cpf);
        var registered = digits.length() == 11 && employeeRepository.existsByCpf(digits);
        return new PublicEmployeeRegistrationDtos.CpfCheckResponse(registered);
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
        var validity = resolveEmployeeValidity(request.accessValidFrom(), request.accessValidUntil());
        employee.setAccessValidFrom(validity.validFrom());
        employee.setAccessValidUntil(validity.validUntil());
        employee.markPendingSync();
        applyFullAccessAreas(employee);
        auditService.record("EMPLOYEE_UPDATED", "Employee", employee.getId(), Map.of("cpf", employee.getCpf(), "role", employee.getRole()),
                oldData, employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeUpdatedEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse patch(UUID id, EmployeePatchRequest request) {
        var employee = employeeRepository.findByIdWithAllowedAreas(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
        var oldData = employeeSnapshot(employee);
        var email = normalizeEmail(request.email());
        updateLinkedUserForPatch(employee, request.fullName().trim(), email);
        employee.setFullName(request.fullName().trim());
        employee.setEmail(email);
        employee.setPhone(normalize(request.phone()));
        employee.setJobTitle(normalize(request.jobTitle()));
        if (request.allowedAreaIds() != null) {
            employee.replaceAllowedAreas(resolvePatchAreas(request.allowedAreaIds()));
        }
        employeeRepository.save(employee);
        auditService.record("EMPLOYEE_PATCHED", "Employee", employee.getId(),
                Map.of("cpf", employee.getCpf()),
                oldData,
                employeeSnapshot(employee));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse requestSync(UUID id) {
        var employee = getEmployee(id);
        validateSyncEligibility(employee);
        var oldData = employeeSnapshot(employee);
        queueEmployeeSync(employee, oldData);
        return EmployeeResponse.from(employee);
    }

    private void validateSyncEligibility(Employee employee) {
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("Colaborador precisa estar ativo para sincronizar.");
        }
        // Aceita: foto facial ou cartão RFID físico. Sem cartão físico, a Intelbras usa CPF completo como CardNo.
        if (!hasText(employee.getFacePhotoUrl())
                && !hasText(employee.getCardNo())) {
            throw new IllegalArgumentException(
                    "Informe foto facial ou tag/cartão antes da sincronização.");
        }
    }

    private void queueEmployeeSync(Employee employee, Map<String, Object> oldData) {
        if (employee.getAllowedAreas() == null || employee.getAllowedAreas().isEmpty()) {
            applyFullAccessAreas(employee);
        }
        employee.markPendingSync();
        employeeRepository.save(employee);
        auditService.record("EMPLOYEE_SYNC_REQUESTED", "Employee", employee.getId(),
                Map.of(
                        "validFrom", employee.getAccessValidFrom() == null ? "" : employee.getAccessValidFrom(),
                        "validUntil", employee.getAccessValidUntil() == null ? "" : employee.getAccessValidUntil(),
                        "cardNo", employee.getCardNo() == null ? "" : employee.getCardNo(),
                        "allowedAreaIds", activeAllowedAreaIds(employee)
                ),
                oldData,
                employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeReadyForSyncEvent(employee.getId()));
    }

    private void triggerEmployeeAutoSyncAfterRegistration(Employee employee, String source) {
        if (employee == null || employee.getId() == null) {
            return;
        }
        if (isAutoSyncAlreadyDoneOrRunning(employee.getSyncStatus())) {
            log.info("EMPLOYEE_AUTO_SYNC_SKIPPED employee_id={} source={} sync_status={} reason=already_synced_or_syncing",
                    employee.getId(), source, employee.getSyncStatus());
            return;
        }
        try {
            log.info("EMPLOYEE_AUTO_SYNC_TRIGGERED employee_id={} source={}", employee.getId(), source);
            requestSync(employee.getId());
        } catch (Exception exception) {
            log.warn("AUTO_SYNC_FAILED_AFTER_REGISTRATION person_type=EMPLOYEE employee_id={} source={} error={}",
                    employee.getId(), source, exception.getMessage(), exception);
        }
    }

    private boolean isAutoSyncAlreadyDoneOrRunning(br.com.sport.accesscontrol.integration.sync.SyncStatus syncStatus) {
        return syncStatus == br.com.sport.accesscontrol.integration.sync.SyncStatus.SYNCED
                || syncStatus == br.com.sport.accesscontrol.integration.sync.SyncStatus.SYNCING;
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

    private void validatePublicRegistration(String fullName, String cpf, String phone, String email,
                                            MultipartFile facePhoto, String facePhotoBase64) {
        if (!hasText(fullName)) {
            throw new IllegalArgumentException("Nome completo é obrigatório.");
        }
        if (!hasText(cpf)) {
            throw new IllegalArgumentException("CPF é obrigatório.");
        }
        if (!hasText(phone)) {
            throw new IllegalArgumentException("Telefone é obrigatório.");
        }
        if (!hasText(email)) {
            throw new IllegalArgumentException("E-mail é obrigatório.");
        }
    }

    private String storePublicEmployeeFace(MultipartFile facePhoto, String facePhotoBase64, UUID employeeId) {
        if (facePhoto != null && !facePhoto.isEmpty()) {
            return faceStorageService.store(facePhoto, employeeId);
        }
        if (!hasText(facePhotoBase64)) {
            return null;
        }
        return faceStorageService.storeBase64(facePhotoBase64, employeeId);
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

    private void updateLinkedUserForPatch(Employee employee, String fullName, String email) {
        if (!hasText(email)) {
            throw new IllegalArgumentException("Employee email is required.");
        }
        if (employee.getUserId() == null) {
            userRepository.findByEmailIgnoreCase(email)
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Email already registered");
                    });
            return;
        }
        var user = userRepository.findById(employee.getUserId()).orElse(null);
        if (user == null) {
            employee.setUserId(null);
            return;
        }
        var currentUser = user;
        userRepository.findByEmailIgnoreCase(email)
                .filter(existing -> !existing.getId().equals(currentUser.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Email already registered");
                });
        user.setName(fullName);
        user.setEmail(email);
        employee.setUserId(user.getId());
    }

    private LinkedHashSet<br.com.sport.accesscontrol.areas.Area> resolvePatchAreas(List<UUID> allowedAreaIds) {
        if (loungeAreaResolver == null) {
            throw new IllegalArgumentException("Áreas de acesso não estão disponíveis.");
        }
        var requestedIds = allowedAreaIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (requestedIds.isEmpty()) {
            throw new IllegalArgumentException("Selecione pelo menos uma área de acesso.");
        }
        var activeAreasById = loungeAreaResolver.findAllByIds(requestedIds).stream()
                .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                .collect(java.util.stream.Collectors.toMap(
                        br.com.sport.accesscontrol.areas.Area::getId,
                        area -> area
                ));
        if (activeAreasById.size() != requestedIds.size()) {
            throw new IllegalArgumentException("Selecione apenas áreas de acesso ativas.");
        }
        return requestedIds.stream()
                .map(activeAreasById::get)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Object> employeeSnapshot(Employee employee) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", employee.getId());
        snapshot.put("fullName", employee.getFullName());
        snapshot.put("cpf", employee.getCpf());
        snapshot.put("email", employee.getEmail() == null ? "" : employee.getEmail());
        snapshot.put("jobTitle", employee.getJobTitle() == null ? "" : employee.getJobTitle());
        snapshot.put("cardNo", employee.getCardNo() == null ? "" : employee.getCardNo());
        snapshot.put("role", employee.getRole() == null ? "" : employee.getRole());
        snapshot.put("userId", employee.getUserId() == null ? "" : employee.getUserId());
        snapshot.put("status", employee.getStatus());
        snapshot.put("accessValidFrom", employee.getAccessValidFrom() == null ? "" : employee.getAccessValidFrom());
        snapshot.put("accessValidUntil", employee.getAccessValidUntil() == null ? "" : employee.getAccessValidUntil());
        snapshot.put("syncStatus", employee.getSyncStatus());
        return snapshot;
    }

    private EmployeeValidity resolveEmployeeValidity(Instant requestedFrom, Instant requestedUntil) {
        var validFrom = requestedFrom == null ? Instant.now() : requestedFrom;
        var validUntil = requestedUntil == null ? validFrom.plus(DEFAULT_EMPLOYEE_VALIDITY) : requestedUntil;
        return new EmployeeValidity(validFrom, validUntil);
    }

    private record EmployeeValidity(Instant validFrom, Instant validUntil) {
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCpf(String cpf) {
        return CpfValidator.normalizeOrThrow(cpf);
    }

    private String normalizeCardNo(String cardNo) {
        var normalized = normalize(cardNo);
        if (normalized == null) return null;
        var upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.matches(".*[A-F].*") && upper.matches("[0-9A-F:. \\-]+")) {
            log.warn("CARD_FORMAT_WARNING card_no_raw={} — contains hex letters (A-F). Intelbras expects decimal (numeric only). Convert to decimal first, or non-digit chars will be stripped.",
                    normalized);
        }
        var digitsOnly = normalized.replaceAll("\\D", "");
        if (digitsOnly.isBlank() && !normalized.isBlank()) {
            log.warn("CARD_ALL_DIGITS_STRIPPED card_no_raw={} — all chars stripped (input was all non-digits). Card will not be sent to Intelbras.",
                    normalized);
        }
        return digitsOnly.isBlank() ? null : digitsOnly;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> activeAllowedAreaIds(Employee employee) {
        if (employee.getAllowedAreas() == null) {
            return List.of();
        }
        return employee.getAllowedAreas().stream()
                .filter(br.com.sport.accesscontrol.areas.Area::isActive)
                .map(br.com.sport.accesscontrol.areas.Area::getId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
