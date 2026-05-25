package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.users.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record EmployeeRequest(
        @NotBlank String fullName,
        @NotBlank String cpf,
        @Email String email,
        String phone,
        String registrationNumber,
        String cardNo,
        String facePhotoUrl,
        @Size(min = 8) String password,
        UserRole role,
        EmployeeStatus status,
        Instant accessValidFrom,
        Instant accessValidUntil
) {
}
