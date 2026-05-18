package br.com.sport.accesscontrol.employees;

import java.time.Instant;
import java.util.UUID;

public record EmployeeResponse(
        UUID id,
        String fullName,
        String cpf,
        String email,
        String phone,
        String registrationNumber,
        String facePhotoUrl,
        EmployeeStatus status,
        Instant accessValidFrom,
        Instant accessValidUntil,
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
                employee.getFacePhotoUrl(),
                employee.getStatus(),
                employee.getAccessValidFrom(),
                employee.getAccessValidUntil(),
                employee.getCreatedAt(),
                employee.getUpdatedAt()
        );
    }
}
