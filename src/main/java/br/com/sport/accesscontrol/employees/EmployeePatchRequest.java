package br.com.sport.accesscontrol.employees;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record EmployeePatchRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        String phone,
        String jobTitle,
        List<UUID> allowedAreaIds
) {
}
