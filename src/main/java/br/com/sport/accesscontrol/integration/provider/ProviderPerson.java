package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.UUID;

public record ProviderPerson(
        PersonType personType,
        UUID personId,
        String document,
        String cardNo,
        String fullName,
        String facePhotoUrl,
        boolean active,
        Instant validFrom,
        Instant validUntil,
        UUID areaId
) {
    public ProviderPerson(PersonType personType, UUID personId, String document, String fullName, String facePhotoUrl,
                          boolean active, Instant validFrom, Instant validUntil) {
        this(personType, personId, document, null, fullName, facePhotoUrl, active, validFrom, validUntil, null);
    }

    public ProviderPerson(PersonType personType, UUID personId, String document, String cardNo, String fullName,
                          String facePhotoUrl, boolean active, Instant validFrom, Instant validUntil) {
        this(personType, personId, document, cardNo, fullName, facePhotoUrl, active, validFrom, validUntil, null);
    }
}
