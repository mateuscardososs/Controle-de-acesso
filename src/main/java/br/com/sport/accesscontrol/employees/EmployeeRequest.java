package br.com.sport.accesscontrol.employees;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record EmployeeRequest(
        @NotBlank String fullName,
        @NotBlank String cpf,
        @Email String email,
        String phone,
        String registrationNumber,
        String facePhotoUrl,
        EmployeeStatus status,
        Instant accessValidFrom,
        Instant accessValidUntil
) {
}
