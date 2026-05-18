package br.com.sport.accesscontrol.common.events;

import java.util.UUID;

public record EmployeeDeactivatedEvent(UUID employeeId) {
}
