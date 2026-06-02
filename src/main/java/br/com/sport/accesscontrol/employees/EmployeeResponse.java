package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.users.UserRole;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record EmployeeResponse(
        UUID id,
        String fullName,
        String cpf,
        String email,
        String phone,
        String registrationNumber,
        String jobTitle,
        String cardNo,
        String intelbrasCardNo,
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
        int syncTargetCount,
        int syncSuccessCount,
        int syncFailedCount,
        int syncSkippedCount,
        Instant createdAt,
        Instant updatedAt,
        List<UUID> allowedAreaIds,
        List<String> allowedAreaNames,
        String displayAllowedAreas,
        boolean fullAccess
) {
    static EmployeeResponse from(Employee employee) {
        List<Area> allowed = employee.getAllowedAreas() == null
                ? Collections.emptyList()
                : employee.getAllowedAreas().stream().toList();
        List<UUID> allowedIds = allowed.stream().map(Area::getId).toList();
        List<String> allowedNames = allowed.stream().map(Area::getName).toList();
        String display = allowed.isEmpty()
                ? null
                : allowed.stream()
                        .map(Area::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .reduce((first, second) -> first + ", " + second)
                        .orElse(null);
        return new EmployeeResponse(
                employee.getId(),
                employee.getFullName(),
                employee.getCpf(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getRegistrationNumber(),
                employee.getJobTitle(),
                employee.getCardNo(),
                employee.getIntelbrasCardNo(),
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
                employee.getSyncTargetCount(),
                employee.getSyncSuccessCount(),
                employee.getSyncFailedCount(),
                employee.getSyncSkippedCount(),
                employee.getCreatedAt(),
                employee.getUpdatedAt(),
                allowedIds,
                allowedNames,
                display,
                false
        );
    }
}
