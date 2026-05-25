package br.com.sport.accesscontrol.integration.intelbras.model;

import br.com.sport.accesscontrol.common.PersonType;

import java.util.UUID;

public record IntelbrasPersonIdentity(
        PersonType personType,
        UUID personId,
        String personName,
        String personCpf,
        String externalUserId,
        String rawCardName,
        boolean foundInDatabase
) {
    public IntelbrasPersonIdentity(PersonType personType, UUID personId) {
        this(personType, personId, null, null, null, null, personId != null);
    }
}
