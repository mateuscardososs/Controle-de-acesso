package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.users.UserRole;

import java.time.Instant;
import java.util.UUID;

public record EmployeeResponse(
        UUID id,
        String fullName,
        String cpf,
        String email,
        String phone,
        String registrationNumber,
        String cardNo,
        String facePhotoUrl,
        UUID userId,
        UserRole role,
        EmployeeStatus status,
        Instant accessValidFrom,
        Instant accessValidUntil,
        SyncStatus syncStatus,
        Instant lastSyncAt,
        String lastSyncError,
        int syncAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getFullName(),
                employee.getCpf(),
                    employee.getEmail(),
                    employee.getPhone(),
                    employee.getRegistrationNumber(),
                    employee.getCardNo(),
                    employee.getFacePhotoUrl(),
                employee.getUserId(),
                employee.getRole(),
                employee.getStatus(),
                employee.getAccessValidFrom(),
                employee.getAccessValidUntil(),
                employee.getSyncStatus(),
                employee.getLastSyncAt(),
                employee.getLastSyncError(),
                employee.getSyncAttempts(),
                employee.getCreatedAt(),
                employee.getUpdatedAt()
        );
    }
}
