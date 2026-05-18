package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.UUID;

public record ProviderPermission(
        PersonType personType,
        UUID personId,
        UUID areaId,
        boolean active,
        Instant validFrom,
        Instant validUntil
) {
}
