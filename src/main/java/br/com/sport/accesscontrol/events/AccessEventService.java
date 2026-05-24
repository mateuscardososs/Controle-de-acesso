package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.events.AccessEventReceivedEvent;
import br.com.sport.accesscontrol.devices.DeviceService;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AccessEventService {

    private static final int EXPORT_MAX_ROWS = 10_000;
    private static final DateTimeFormatter CSV_INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final AccessEventRepository accessEventRepository;
    private final DeviceService deviceService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RealtimePublisherService realtimePublisherService;
    private final AccessMetricsService accessMetricsService;
    private final IntelbrasProperties intelbrasProperties;
    private final GuestRepository guestRepository;
    private final EmployeeRepository employeeRepository;

    public AccessEventService(AccessEventRepository accessEventRepository, DeviceService deviceService,
                              ApplicationEventPublisher eventPublisher, AuditService auditService,
                              RealtimePublisherService realtimePublisherService,
                              AccessMetricsService accessMetricsService,
                              IntelbrasProperties intelbrasProperties) {
        this(accessEventRepository, deviceService, eventPublisher, auditService, realtimePublisherService,
                accessMetricsService, intelbrasProperties, null, null);
    }

    @Autowired
    public AccessEventService(AccessEventRepository accessEventRepository, DeviceService deviceService,
                              ApplicationEventPublisher eventPublisher, AuditService auditService,
                              RealtimePublisherService realtimePublisherService,
                              AccessMetricsService accessMetricsService,
                              IntelbrasProperties intelbrasProperties,
                              GuestRepository guestRepository,
                              EmployeeRepository employeeRepository) {
        this.accessEventRepository = accessEventRepository;
        this.deviceService = deviceService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
        this.accessMetricsService = accessMetricsService;
        this.intelbrasProperties = intelbrasProperties;
        this.guestRepository = guestRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public AccessEventResponse simulate(AccessEventSimulationRequest request) {
        var device = deviceService.getById(request.deviceId());
        var accessEvent = new AccessEvent(
                request.personType(),
                request.personId(),
                device,
                device.getArea(),
                request.eventType(),
                request.accessResult(),
                request.eventTime(),
                request.origin(),
                request.rawPayload()
        );
        enrichPersonSnapshot(accessEvent);
        var saved = accessEventRepository.save(accessEvent);
        accessMetricsService.recordAccessEvent(saved);
        auditService.record("ACCESS_EVENT_RECEIVED", "AccessEvent", saved.getId(),
                Map.of("origin", saved.getOrigin(), "result", saved.getAccessResult()), Map.of(), Map.of("id", saved.getId()));
        realtimePublisherService.publishAccessEvent(saved);
        eventPublisher.publishEvent(new AccessEventReceivedEvent(saved.getId()));
        return AccessEventResponse.from(saved);
    }

    @Transactional
    public Optional<AccessEventResponse> recordImported(NormalizedAccessEvent normalized) {
        var device = deviceService.getById(normalized.deviceId());
        var recNo = rawText(normalized.rawPayload(), "RecNo");
        if (recNo != null && accessEventRepository.existsByDeviceIdAndOriginAndIntelbrasRecNo(
                device.getId(),
                normalized.origin(),
                recNo
        )) {
            return Optional.empty();
        }
        if (recNo == null && accessEventRepository.existsByDeviceIdAndOriginAndIntelbrasNaturalKey(
                device.getId(),
                normalized.origin(),
                rawText(normalized.rawPayload(), "CreateTime"),
                rawText(normalized.rawPayload(), "UserID"),
                rawText(normalized.rawPayload(), "Door"),
                rawText(normalized.rawPayload(), "Method")
        )) {
            return Optional.empty();
        }
        if (recNo == null && accessEventRepository.existsByDeviceIdAndOriginAndIntelbrasDedupWindow(
                device.getId(),
                normalized.origin(),
                normalized.eventTime().minus(intelbrasProperties.getDedupWindow()),
                normalized.eventTime().plus(intelbrasProperties.getDedupWindow()),
                rawText(normalized.rawPayload(), "UserID"),
                rawText(normalized.rawPayload(), "Door"),
                rawText(normalized.rawPayload(), "Method")
        )) {
            return Optional.empty();
        }
        if (normalized.personId() != null && accessEventRepository.existsByDevice_IdAndPersonIdAndEventTimeAndOrigin(
                device.getId(),
                normalized.personId(),
                normalized.eventTime(),
                normalized.origin()
        )) {
            return Optional.empty();
        }
        var accessEvent = new AccessEvent(
                normalized.personType(),
                normalized.personId(),
                normalized.personName(),
                normalized.personCpf(),
                normalized.externalUserId(),
                normalized.rawCardName(),
                device,
                device.getArea(),
                normalized.eventType(),
                normalized.accessResult(),
                normalized.eventTime(),
                normalized.origin(),
                normalized.rawPayload()
        );
        accessEvent.applyOperationalFields(
                normalized.accessResult() == AccessResult.ERROR ? EventCategory.COMMUNICATION : EventCategory.ACCESS_DECISION,
                normalized.personId() == null ? RecognitionStatus.NOT_RECOGNIZED : RecognitionStatus.RECOGNIZED,
                normalized.accessResult() == AccessResult.ALLOWED ? PassageStatus.PASSED : PassageStatus.NOT_PASSED,
                releaseMethod(rawText(normalized.rawPayload(), "Method")),
                null,
                null,
                rawText(normalized.rawPayload(), "Method"),
                rawText(normalized.rawPayload(), "Door"),
                rawText(normalized.rawPayload(), "ReaderID"),
                rawText(normalized.rawPayload(), "RecNo"),
                rawText(normalized.rawPayload(), "ErrorCode"),
                normalized.eventTime()
        );
        enrichPersonSnapshot(accessEvent);
        var saved = accessEventRepository.save(accessEvent);
        accessMetricsService.recordAccessEvent(saved);
        auditService.record("ACCESS_EVENT_IMPORTED", "AccessEvent", saved.getId(),
                Map.of("origin", saved.getOrigin(), "result", saved.getAccessResult()), Map.of(), Map.of("id", saved.getId()));
        realtimePublisherService.publishAccessEvent(saved);
        org.slf4j.LoggerFactory.getLogger(AccessEventService.class).info(
                "event_publish_realtime access_event_id={} device_id={} origin={}",
                saved.getId(), device.getId(), saved.getOrigin());
        eventPublisher.publishEvent(new AccessEventReceivedEvent(saved.getId()));
        return Optional.of(AccessEventResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public List<AccessEventResponse> findAll() {
        return accessEventRepository.findAllByOrderByEventTimeDesc().stream().map(AccessEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccessEventPageResponse search(AccessEventSearchRequest request) {
        var page = Math.max(request.page(), 0);
        var size = Math.min(Math.max(request.size(), 1), 200);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventTime"));
        return AccessEventPageResponse.from(accessEventRepository.findAll(specification(request), pageable)
                .map(AccessEventResponse::from));
    }

    @Transactional(readOnly = true)
    public String exportCsv(AccessEventSearchRequest request) {
        var size = Math.min(Math.max(request.size(), 1), EXPORT_MAX_ROWS);
        var pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "eventTime"));
        var events = accessEventRepository.findAll(specification(request), pageable).getContent();
        var csv = new StringBuilder();
        appendRow(csv, List.of(
                "horário",
                "pessoa",
                "CPF",
                "telefone",
                "e-mail",
                "dia convidado",
                "camarote",
                "catraca/controladora",
                "entrada/local",
                "resultado",
                "reconhecimento",
                "passagem",
                "método/liberação",
                "operador",
                "motivo manual"
        ));
        events.forEach(event -> appendRow(csv, java.util.Arrays.asList(
                formatInstant(event.getOccurredAt() == null ? event.getEventTime() : event.getOccurredAt()),
                firstNonBlank(event.getPersonName(), event.getRawCardName(), event.getExternalUserId(), "Usuário não identificado"),
                event.getPersonCpf(),
                event.getPersonPhone(),
                event.getPersonEmail(),
                event.getInvitedDay() == null ? null : event.getInvitedDay().toString(),
                event.getInvitedLounge(),
                event.getDevice() == null ? null : event.getDevice().getName(),
                event.getArea() == null ? null : event.getArea().getName(),
                enumName(event.getAccessResult()),
                enumName(event.getRecognitionStatus()),
                enumName(event.getPassageStatus()),
                firstNonBlank(enumName(event.getReleaseMethod()), event.getControllerMethod()),
                firstNonBlank(rawText(event.getRawPayload(), "operatorName"),
                        event.getOperatorUserId() == null ? null : event.getOperatorUserId().toString()),
                firstNonBlank(event.getManualReason(), event.getDecisionReason(), rawText(event.getRawPayload(), "reason"))
        )));
        return csv.toString();
    }

    @Transactional
    public AccessEventResponse manualRelease(ManualAccessReleaseRequest request, Authentication authentication) {
        var device = deviceService.getById(request.deviceId());
        var now = Instant.now();
        var operator = operator(authentication);
        var rawPayload = new LinkedHashMap<String, Object>();
        var personCpf = hasText(request.personCpf()) ? onlyDigits(request.personCpf()) : null;
        rawPayload.put("source", "manual-release");
        rawPayload.put("personName", request.personName());
        rawPayload.put("personCpf", personCpf);
        rawPayload.put("deviceId", request.deviceId());
        rawPayload.put("deviceName", device.getName());
        rawPayload.put("areaId", device.getArea().getId());
        rawPayload.put("reason", request.reason());
        rawPayload.put("operatorObservation", request.operatorObservation());
        rawPayload.put("operatorUserId", operator.id());
        rawPayload.put("operatorName", operator.name());
        rawPayload.put("operatorEmail", operator.email());
        rawPayload.put("occurredAt", now.toString());

        var accessEvent = new AccessEvent(
                PersonType.UNKNOWN,
                null,
                request.personName(),
                personCpf,
                null,
                null,
                device,
                device.getArea(),
                AccessEventType.MANUAL_ADMIN_RELEASE,
                AccessResult.ALLOWED,
                now,
                "MANUAL_ADMIN_RELEASE",
                rawPayload
        );
        accessEvent.applyOperationalFields(
                EventCategory.MANUAL_RELEASE,
                RecognitionStatus.NOT_APPLICABLE,
                PassageStatus.PASSED,
                ReleaseMethod.MANUAL_ADMIN_RELEASE,
                operator.id(),
                request.reason(),
                null,
                null,
                null,
                null,
                request.operatorObservation(),
                now
        );
        enrichPersonSnapshot(accessEvent);
        var saved = accessEventRepository.save(accessEvent);
        accessMetricsService.recordAccessEvent(saved);
        auditService.record("MANUAL_ADMIN_RELEASE", "AccessEvent", saved.getId(),
                Map.of("deviceId", device.getId(), "operatorUserId", operator.id(), "reason", request.reason()),
                Map.of(),
                Map.of("id", saved.getId()));
        realtimePublisherService.publishAccessEvent(saved);
        eventPublisher.publishEvent(new AccessEventReceivedEvent(saved.getId()));
        return AccessEventResponse.from(saved);
    }

    private Specification<AccessEvent> specification(AccessEventSearchRequest request) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (request.startDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTime"), request.startDate()));
            }
            if (request.endDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTime"), request.endDate()));
            }
            if (hasText(request.personName())) {
                predicates.add(cb.like(cb.lower(root.get("personName")), "%" + request.personName().toLowerCase(Locale.ROOT).trim() + "%"));
            }
            if (hasText(request.personCpf())) {
                predicates.add(cb.like(root.get("personCpf"), "%" + onlyDigits(request.personCpf()) + "%"));
            }
            if (request.invitedDay() != null) {
                predicates.add(cb.equal(root.get("invitedDay"), request.invitedDay()));
            }
            if (hasText(request.invitedLounge())) {
                predicates.add(cb.equal(root.get("invitedLounge"), request.invitedLounge().trim()));
            }
            if (request.deviceId() != null) {
                predicates.add(cb.equal(root.get("device").get("id"), request.deviceId()));
            }
            if (request.areaId() != null) {
                predicates.add(cb.equal(root.get("area").get("id"), request.areaId()));
            }
            if (request.eventType() != null) {
                predicates.add(cb.equal(root.get("eventType"), request.eventType()));
            }
            if (request.accessResult() != null) {
                predicates.add(cb.equal(root.get("accessResult"), request.accessResult()));
            }
            if (request.recognitionStatus() != null) {
                predicates.add(cb.equal(root.get("recognitionStatus"), request.recognitionStatus()));
            }
            if (request.passageStatus() != null) {
                predicates.add(cb.equal(root.get("passageStatus"), request.passageStatus()));
            }
            if (request.releaseMethod() != null) {
                predicates.add(cb.equal(root.get("releaseMethod"), request.releaseMethod()));
            }
            if (hasText(request.origin())) {
                predicates.add(cb.equal(root.get("origin"), request.origin().trim()));
            }
            if (Boolean.TRUE.equals(request.manualOnly())) {
                predicates.add(cb.equal(root.get("releaseMethod"), ReleaseMethod.MANUAL_ADMIN_RELEASE));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private UserPrincipal operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalArgumentException("Authenticated operator is required.");
        }
        return principal;
    }

    private void enrichPersonSnapshot(AccessEvent accessEvent) {
        var snapshot = resolveSnapshot(accessEvent);
        if (snapshot == null) {
            snapshot = resolveSnapshotByCpf(accessEvent.getPersonCpf());
        }
        if (snapshot == null) {
            snapshot = resolveSnapshotByCpf(accessEvent.getExternalUserId());
        }
        if (snapshot == null) {
            snapshot = resolveSnapshotByCpf(rawText(accessEvent.getRawPayload(), "UserID"));
        }
        if (snapshot == null) {
            snapshot = resolveSnapshotByCpf(rawText(accessEvent.getRawPayload(), "CardNo"));
        }
        if (snapshot == null) {
            snapshot = resolveSnapshotByName(accessEvent.getRawCardName());
        }
        if (snapshot == null) {
            snapshot = resolveSnapshotByName(accessEvent.getPersonName());
        }
        if (snapshot == null) {
            return;
        }
        accessEvent.applyPersonSnapshot(
                snapshot.name(),
                snapshot.cpf(),
                snapshot.email(),
                snapshot.phone(),
                snapshot.invitedDay(),
                snapshot.invitedLounge()
        );
    }

    private PersonSnapshot resolveSnapshot(AccessEvent accessEvent) {
        if (guestRepository == null || employeeRepository == null) {
            return null;
        }
        if (accessEvent.getPersonId() == null) {
            return null;
        }
        if (accessEvent.getPersonType() == PersonType.GUEST) {
            return guestRepository.findById(accessEvent.getPersonId())
                    .map(guest -> new PersonSnapshot(
                            guest.getFullName(),
                            guest.getCpf(),
                            guest.getEmail(),
                            guest.getPhone(),
                            guest.getInvitedDay(),
                            guest.getInvitedLounge()
                    ))
                    .orElse(null);
        }
        if (accessEvent.getPersonType() == PersonType.EMPLOYEE) {
            return employeeRepository.findById(accessEvent.getPersonId())
                    .map(employee -> new PersonSnapshot(
                            employee.getFullName(),
                            employee.getCpf(),
                            employee.getEmail(),
                            employee.getPhone(),
                            null,
                            null
                    ))
                    .orElse(null);
        }
        return null;
    }

    private PersonSnapshot resolveSnapshotByCpf(String cpf) {
        if (guestRepository == null || employeeRepository == null) {
            return null;
        }
        if (!hasText(cpf)) {
            return null;
        }
        var normalizedCpf = onlyDigits(cpf);
        var guest = guestRepository.findFirstByCpfOrderByVisitStartDesc(normalizedCpf);
        if (guest.isPresent()) {
            var found = guest.get();
            return new PersonSnapshot(
                    found.getFullName(),
                    found.getCpf(),
                    found.getEmail(),
                    found.getPhone(),
                    found.getInvitedDay(),
                    found.getInvitedLounge()
            );
        }
        return employeeRepository.findByCpf(normalizedCpf)
                .map(employee -> new PersonSnapshot(
                        employee.getFullName(),
                        employee.getCpf(),
                        employee.getEmail(),
                        employee.getPhone(),
                        null,
                        null
                ))
                .orElse(null);
    }

    private PersonSnapshot resolveSnapshotByName(String name) {
        if (guestRepository == null || employeeRepository == null) {
            return null;
        }
        if (!hasText(name) || "Usuário não identificado".equalsIgnoreCase(name.trim())) {
            return null;
        }
        var guest = guestRepository.findFirstByFullNameIgnoreCaseOrderByVisitStartDesc(name.trim());
        if (guest != null && guest.isPresent()) {
            var found = guest.get();
            return new PersonSnapshot(
                    found.getFullName(),
                    found.getCpf(),
                    found.getEmail(),
                    found.getPhone(),
                    found.getInvitedDay(),
                    found.getInvitedLounge()
            );
        }
        var employee = employeeRepository.findFirstByFullNameIgnoreCase(name.trim());
        return (employee == null ? Optional.<br.com.sport.accesscontrol.employees.Employee>empty() : employee)
                .map(found -> new PersonSnapshot(
                        found.getFullName(),
                        found.getCpf(),
                        found.getEmail(),
                        found.getPhone(),
                        null,
                        null
                ))
                .orElse(null);
    }

    private ReleaseMethod releaseMethod(String method) {
        var value = method == null ? "" : method.toLowerCase(Locale.ROOT);
        if (value.contains("face") || value.contains("facial")) {
            return ReleaseMethod.FACIAL_RECOGNITION;
        }
        if (value.contains("card") || value.contains("cart")) {
            return ReleaseMethod.CARD;
        }
        return ReleaseMethod.UNKNOWN;
    }

    private String rawText(Map<String, Object> rawPayload, String key) {
        if (rawPayload == null) {
            return null;
        }
        var value = rawPayload.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private void appendRow(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(csvValue(values.get(index)));
        }
        csv.append("\r\n");
    }

    private String csvValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var trimmed = value.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            trimmed = "'" + trimmed;
        }
        return "\"" + trimmed.replace("\"", "\"\"") + "\"";
    }

    private String formatInstant(Instant instant) {
        return instant == null ? null : CSV_INSTANT_FORMATTER.format(instant);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String onlyDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private record PersonSnapshot(
            String name,
            String cpf,
            String email,
            String phone,
            java.time.LocalDate invitedDay,
            String invitedLounge
    ) {
    }
}
