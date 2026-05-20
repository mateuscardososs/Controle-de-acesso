package br.com.sport.accesscontrol.realtime;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEvent;
import br.com.sport.accesscontrol.guests.GuestRepository;
import br.com.sport.accesscontrol.realtime.dto.RealtimeAccessEventMessage;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RealtimeAccessEventMapper {

    private final EmployeeRepository employeeRepository;
    private final GuestRepository guestRepository;

    public RealtimeAccessEventMapper(EmployeeRepository employeeRepository, GuestRepository guestRepository) {
        this.employeeRepository = employeeRepository;
        this.guestRepository = guestRepository;
    }

    public RealtimeAccessEventMessage toMessage(AccessEvent event) {
        var person = resolvePerson(event);
        var device = safeDevice(event);
        var area = safeArea(event, device);

        return new RealtimeAccessEventMessage(
                event.getId(),
                event.getPersonType(),
                event.getPersonId(),
                person.name(),
                person.cpf(),
                event.getExternalUserId(),
                event.getRawCardName(),
                device == null ? null : safeUuid(device::getId),
                device == null ? null : safeString(device::getName),
                area == null ? null : safeUuid(area::getId),
                area == null ? null : safeString(area::getName),
                event.getEventType(),
                event.getAccessResult(),
                event.getEventTime(),
                event.getOrigin(),
                event.getOrigin(),
                event.getCreatedAt()
        );
    }

    private PersonSnapshot resolvePerson(AccessEvent event) {
        if (event.getPersonName() != null || event.getPersonCpf() != null) {
            return new PersonSnapshot(event.getPersonName(), event.getPersonCpf());
        }
        if (event.getPersonId() == null) {
            return PersonSnapshot.empty();
        }

        if (event.getPersonType() == PersonType.EMPLOYEE) {
            return employeeRepository.findById(event.getPersonId())
                    .map(employee -> new PersonSnapshot(employee.getFullName(), employee.getCpf()))
                    .orElse(PersonSnapshot.empty());
        }

        if (event.getPersonType() == PersonType.GUEST) {
            return guestRepository.findById(event.getPersonId())
                    .map(guest -> new PersonSnapshot(guest.getFullName(), guest.getCpf()))
                    .orElse(PersonSnapshot.empty());
        }

        return PersonSnapshot.empty();
    }

    private Device safeDevice(AccessEvent event) {
        try {
            return event.getDevice();
        } catch (EntityNotFoundException | IllegalStateException exception) {
            return null;
        }
    }

    private Area safeArea(AccessEvent event, Device device) {
        try {
            var area = event.getArea();
            if (area != null) {
                return area;
            }
            return device == null ? null : device.getArea();
        } catch (EntityNotFoundException | IllegalStateException exception) {
            return null;
        }
    }

    private UUID safeUuid(UuidSupplier supplier) {
        try {
            return supplier.get();
        } catch (EntityNotFoundException | IllegalStateException exception) {
            return null;
        }
    }

    private String safeString(StringSupplier supplier) {
        try {
            return supplier.get();
        } catch (EntityNotFoundException | IllegalStateException exception) {
            return null;
        }
    }

    private record PersonSnapshot(String name, String cpf) {
        static PersonSnapshot empty() {
            return new PersonSnapshot(null, null);
        }
    }

    @FunctionalInterface
    private interface UuidSupplier {
        UUID get();
    }

    @FunctionalInterface
    private interface StringSupplier {
        String get();
    }
}
