package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventService;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class IntelbrasEventImportService {

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasCgiClient cgiClient;
    private final IntelbrasEventMapper eventMapper;
    private final AccessEventService accessEventService;
    private final EmployeeRepository employeeRepository;
    private final GuestRepository guestRepository;

    public IntelbrasEventImportService(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                       IntelbrasEventMapper eventMapper, AccessEventService accessEventService,
                                       EmployeeRepository employeeRepository, GuestRepository guestRepository) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.eventMapper = eventMapper;
        this.accessEventService = accessEventService;
        this.employeeRepository = employeeRepository;
        this.guestRepository = guestRepository;
    }

    @Transactional
    public IntelbrasEventImportResult importAccessControlEvents(UUID deviceId) {
        var connection = connectionService.connectionFor(deviceId);
        if (!connection.configured()) {
            throw new IllegalArgumentException("Intelbras device credentials are not configured.");
        }
        var records = cgiClient.findAccessControlEvents(connection.host(), connection.username(), connection.password());
        var imported = 0;
        var skipped = 0;
        for (Map<String, Object> record : records) {
            var parsed = eventMapper.parseAccessControlCardRec(record);
            var normalized = eventMapper.normalizeAccessControlCardRec(record, connection.device(), resolve(parsed.userId(), parsed.cardName()));
            if (accessEventService.recordImported(normalized).isPresent()) {
                imported++;
            } else {
                skipped++;
            }
        }
        return new IntelbrasEventImportResult(deviceId, records.size(), imported, skipped);
    }

    private IntelbrasPersonIdentity resolve(String userId, String cardName) {
        var candidate = firstNonBlank(userId, cardName);
        if (candidate == null) {
            return fallback("unknown");
        }
        var exactEmployee = employeeRepository.findByCpf(candidate);
        if (exactEmployee.isPresent()) {
            return new IntelbrasPersonIdentity(PersonType.EMPLOYEE, exactEmployee.get().getId());
        }
        var digits = candidate.replaceAll("\\D", "");
        if (!digits.isBlank()) {
            var employee = employeeRepository.findByCpf(digits);
            if (employee.isPresent()) {
                return new IntelbrasPersonIdentity(PersonType.EMPLOYEE, employee.get().getId());
            }
            var guest = guestRepository.findFirstByCpfOrderByVisitStartDesc(digits);
            if (guest.isPresent()) {
                return new IntelbrasPersonIdentity(PersonType.GUEST, guest.get().getId());
            }
        }
        return fallback(candidate);
    }

    private IntelbrasPersonIdentity fallback(String externalId) {
        return new IntelbrasPersonIdentity(
                PersonType.EMPLOYEE,
                UUID.nameUUIDFromBytes(("intelbras:" + externalId).getBytes(StandardCharsets.UTF_8))
        );
    }

    private String firstNonBlank(String first, String second) {
        return Optional.ofNullable(first).filter(value -> !value.isBlank())
                .orElseGet(() -> Optional.ofNullable(second).filter(value -> !value.isBlank()).orElse(null));
    }
}
