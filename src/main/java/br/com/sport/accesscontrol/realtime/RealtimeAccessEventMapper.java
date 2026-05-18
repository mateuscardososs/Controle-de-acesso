package br.com.sport.accesscontrol.realtime;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEvent;
import br.com.sport.accesscontrol.realtime.dto.RealtimeAccessEventMessage;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RealtimeAccessEventMapper {

    private final EmployeeRepository employeeRepository;

    public RealtimeAccessEventMapper(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
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
                device == null ? null : safeUuid(device::getId),
                device == null ? null : safeString(device::getName),
                area == null ? null : safeUuid(area::getId),
                area == null ? null : safeString(area::getName),
                event.getEventType(),
                event.getAccessResult(),
                event.getEventTime(),
                event.getOrigin(),
                event.getCreatedAt()
        );
    }

    private PersonSnapshot resolvePerson(AccessEvent event) {
        if (event.getPersonType() != PersonType.EMPLOYEE || event.getPersonId() == null) {
            return PersonSnapshot.empty();
        }

        return employeeRepository.findById(event.getPersonId())
                .map(employee -> new PersonSnapshot(employee.getFullName(), employee.getCpf()))
                .orElse(PersonSnapshot.empty());
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
