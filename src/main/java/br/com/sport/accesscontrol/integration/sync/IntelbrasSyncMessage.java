package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record IntelbrasSyncMessage(
        PersonType personType,
        UUID personId,
        int attempt
) {
    @JsonCreator
    public IntelbrasSyncMessage(
            @JsonProperty("personType") PersonType personType,
            @JsonProperty("personId") UUID personId,
            @JsonProperty("attempt") int attempt
    ) {
        this.personType = personType;
        this.personId = personId;
        this.attempt = attempt;
    }

    public IntelbrasSyncMessage nextAttempt() {
        return new IntelbrasSyncMessage(personType, personId, attempt + 1);
    }
}
