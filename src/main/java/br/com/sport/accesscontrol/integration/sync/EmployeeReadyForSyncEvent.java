package br.com.sport.accesscontrol.integration.sync;

import java.util.UUID;

public record EmployeeReadyForSyncEvent(UUID employeeId) {
}
