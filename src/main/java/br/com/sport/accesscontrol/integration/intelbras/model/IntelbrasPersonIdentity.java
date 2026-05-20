package br.com.sport.accesscontrol.integration.intelbras.model;

import br.com.sport.accesscontrol.common.PersonType;

import java.util.UUID;

public record IntelbrasPersonIdentity(
        PersonType personType,
        UUID personId
) {
}
