package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.PersonType;

import java.time.Instant;
import java.util.UUID;

public record IntegrationSyncMessage(
        PersonType personType,
        UUID personId,
        SyncStatus syncStatus,
        String message,
        Instant occurredAt
) {
}
