package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;

import java.util.UUID;

public record IntelbrasSyncMessage(
        PersonType personType,
        UUID personId,
        int attempt
) {
    public IntelbrasSyncMessage nextAttempt() {
        return new IntelbrasSyncMessage(personType, personId, attempt + 1);
    }
}
