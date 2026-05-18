package br.com.sport.accesscontrol.areas;

import jakarta.validation.constraints.NotBlank;

public record AreaRequest(
        @NotBlank String name,
        String description,
        Boolean active
) {
}
