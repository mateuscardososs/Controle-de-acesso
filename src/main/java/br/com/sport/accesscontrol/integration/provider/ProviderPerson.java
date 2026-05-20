package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.UUID;

public record ProviderPerson(
        PersonType personType,
        UUID personId,
        String document,
        String fullName,
        String facePhotoUrl,
        boolean active,
        Instant validFrom,
        Instant validUntil,
        UUID areaId
) {
    public ProviderPerson(PersonType personType, UUID personId, String document, String fullName, String facePhotoUrl,
                          boolean active, Instant validFrom, Instant validUntil) {
        this(personType, personId, document, fullName, facePhotoUrl, active, validFrom, validUntil, null);
    }
}
