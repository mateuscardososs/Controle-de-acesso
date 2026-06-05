package br.com.sport.accesscontrol.analytics;

import java.util.UUID;

public record UserRankItem(
        String personType,
        UUID personId,
        String name,
        String cpf,
        long accessCount
) {}
