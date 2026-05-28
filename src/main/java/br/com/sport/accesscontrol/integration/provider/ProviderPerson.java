package br.com.sport.accesscontrol.integration.provider;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.Set;
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
        UUID areaId,
        Set<UUID> allowedAreaIds
) {
    public ProviderPerson(PersonType personType, UUID personId, String document, String fullName, String facePhotoUrl,
                          boolean active, Instant validFrom, Instant validUntil) {
        this(personType, personId, document, null, fullName, facePhotoUrl, active, validFrom, validUntil, null, null);
    }

    public ProviderPerson(PersonType personType, UUID personId, String document, String cardNo, String fullName,
                          String facePhotoUrl, boolean active, Instant validFrom, Instant validUntil) {
        this(personType, personId, document, cardNo, fullName, facePhotoUrl, active, validFrom, validUntil, null, null);
    }

    public ProviderPerson(PersonType personType, UUID personId, String document, String cardNo, String fullName,
                          String facePhotoUrl, boolean active, Instant validFrom, Instant validUntil,
                          UUID areaId) {
        this(personType, personId, document, cardNo, fullName, facePhotoUrl, active, validFrom, validUntil, areaId, null);
    }
}
