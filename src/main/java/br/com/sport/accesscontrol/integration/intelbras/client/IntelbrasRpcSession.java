package br.com.sport.accesscontrol.integration.intelbras.client;

import java.time.Instant;

public record IntelbrasRpcSession(
        String host,
        String sessionId,
        String username,
        Instant createdAt
) {
}
