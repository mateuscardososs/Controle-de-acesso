package br.com.sport.accesscontrol.integration.provider;

import java.time.Duration;

public record ProviderSyncResult(
        ProviderSyncStatus status,
        String message,
        Duration latency
) {
    public boolean successful() {
        return status == ProviderSyncStatus.SUCCESS;
    }
}
