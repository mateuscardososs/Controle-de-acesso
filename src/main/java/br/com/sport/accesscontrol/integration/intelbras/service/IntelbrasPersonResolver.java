package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class IntelbrasPersonResolver {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasPersonResolver.class);

    private final GuestRepository guestRepository;
    private final EmployeeRepository employeeRepository;

    public IntelbrasPersonResolver(GuestRepository guestRepository, EmployeeRepository employeeRepository) {
        this.guestRepository = guestRepository;
        this.employeeRepository = employeeRepository;
    }

    public IntelbrasPersonIdentity resolve(Device device, IntelbrasAccessControlCardRecord record) {
        var userId = blankToNull(record.userId());
        var documentCandidates = documentCandidates(userId);
        var cardName = blankToNull(record.cardName());

        var guest = findGuestByDocumentCandidates(documentCandidates);
        if (guest.isPresent()) {
            var found = guest.get();
            return logResolved(device, userId, new IntelbrasPersonIdentity(
                    PersonType.GUEST,
                    found.getId(),
                    found.getFullName(),
                    found.getCpf(),
                    userId,
                    cardName,
                    true
            ));
        }

        var employee = findEmployeeByDocumentCandidates(documentCandidates);
        if (employee.isPresent()) {
            var found = employee.get();
            return logResolved(device, userId, new IntelbrasPersonIdentity(
                    PersonType.EMPLOYEE,
                    found.getId(),
                    found.getFullName(),
                    found.getCpf(),
                    userId,
                    cardName,
                    true
            ));
        }

        if (userId != null) {
            for (var candidateGuest : safeGuests()) {
                if (matchesGeneratedIdentity(userId, PersonType.GUEST, candidateGuest.getId())) {
                    return logResolved(device, userId, new IntelbrasPersonIdentity(
                            PersonType.GUEST,
                            candidateGuest.getId(),
                            candidateGuest.getFullName(),
                            candidateGuest.getCpf(),
                            userId,
                            cardName,
                            true
                    ));
                }
            }
            for (var candidateEmployee : safeEmployees()) {
                if (matchesGeneratedIdentity(userId, PersonType.EMPLOYEE, candidateEmployee.getId())) {
                    return logResolved(device, userId, new IntelbrasPersonIdentity(
                            PersonType.EMPLOYEE,
                            candidateEmployee.getId(),
                            candidateEmployee.getFullName(),
                            candidateEmployee.getCpf(),
                            userId,
                            cardName,
                            true
                    ));
                }
            }
        }

        return logResolved(device, userId, new IntelbrasPersonIdentity(
                PersonType.UNKNOWN,
                null,
                cardName == null ? "Usuário não identificado" : cardName,
                null,
                userId,
                cardName,
                false
        ));
    }

    private IntelbrasPersonIdentity logResolved(Device device, String userId, IntelbrasPersonIdentity identity) {
        log.info("intelbras_event_person_resolved deviceId={} userId={} resolvedType={} resolvedName={} foundInDatabase={}",
                device == null ? null : device.getId(),
                userId,
                identity.personType(),
                identity.personName(),
                identity.foundInDatabase());
        return identity;
    }

    private Optional<br.com.sport.accesscontrol.guests.Guest> findGuestByDocumentCandidates(List<String> candidates) {
        for (String candidate : candidates) {
            var guest = guestRepository.findFirstByCpfOrderByVisitStartDesc(candidate);
            if (guest != null && guest.isPresent()) {
                return guest;
            }
        }
        for (var guest : safeGuests()) {
            if (matchesAnyDocumentCandidate(guest.getCpf(), candidates)) {
                return Optional.of(guest);
            }
        }
        return Optional.empty();
    }

    private Optional<br.com.sport.accesscontrol.employees.Employee> findEmployeeByDocumentCandidates(List<String> candidates) {
        for (String candidate : candidates) {
            var employee = employeeRepository.findByCpf(candidate);
            if (employee != null && employee.isPresent()) {
                return employee;
            }
        }
        for (var employee : safeEmployees()) {
            if (matchesAnyDocumentCandidate(employee.getCpf(), candidates)) {
                return Optional.of(employee);
            }
        }
        return Optional.empty();
    }

    private List<String> documentCandidates(String value) {
        var normalized = normalizeCpf(value);
        if (normalized == null) {
            return List.of();
        }
        var candidates = new LinkedHashSet<String>();
        candidates.add(normalized);
        if (normalized.length() < 11) {
            candidates.add("0".repeat(11 - normalized.length()) + normalized);
        }
        var withoutLeadingZeros = normalized.replaceFirst("^0+(?!$)", "");
        candidates.add(withoutLeadingZeros);
        return List.copyOf(candidates);
    }

    private String normalizeCpf(String value) {
        if (value == null) {
            return null;
        }
        var digits = value.replaceAll("\\D", "");
        return !digits.isBlank() && digits.length() <= 11 ? digits : null;
    }

    private boolean matchesAnyDocumentCandidate(String document, List<String> candidates) {
        var currentCandidates = documentCandidates(document);
        if (currentCandidates.isEmpty() || candidates.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (currentCandidates.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesGeneratedIdentity(String userId, PersonType personType, java.util.UUID personId) {
        return userId.equalsIgnoreCase(IntelbrasIdentityCodec.shortAlphanumericUserId(personType, personId))
                || userId.equals(IntelbrasIdentityCodec.shortNumeric(personId));
    }

    private List<br.com.sport.accesscontrol.guests.Guest> safeGuests() {
        var guests = guestRepository.findAll();
        return guests == null ? List.of() : guests;
    }

    private List<br.com.sport.accesscontrol.employees.Employee> safeEmployees() {
        var employees = employeeRepository.findAll();
        return employees == null ? List.of() : employees;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
