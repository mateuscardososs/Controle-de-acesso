package br.com.sport.accesscontrol.employees;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public final class PublicEmployeeRegistrationDtos {
    private static final String SUCCESS_MESSAGE = "Cadastro realizado com sucesso";

    private PublicEmployeeRegistrationDtos() {
    }

    public record PublicEmployeeRegistrationResponse(
            UUID id,
            @JsonProperty("full_name") String fullName,
            String message
    ) {
        static PublicEmployeeRegistrationResponse from(Employee employee) {
            return new PublicEmployeeRegistrationResponse(employee.getId(), employee.getFullName(), SUCCESS_MESSAGE);
        }
    }

    public record CpfCheckResponse(boolean registered) {
    }
}
