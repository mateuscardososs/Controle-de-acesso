package br.com.sport.accesscontrol.integration.provider;

import java.time.Duration;

public record ProviderSyncResult(
        ProviderSyncStatus status,
        String message,
        Duration latency,
        int totalTargets,
        int successCount,
        int failedCount,
        int skippedCount
) {
    public ProviderSyncResult(ProviderSyncStatus status, String message, Duration latency) {
        this(status, message, latency, defaultTotalTargets(status), defaultSuccessCount(status),
                defaultFailedCount(status), 0);
    }

    public boolean successful() {
        return status == ProviderSyncStatus.SUCCESS
                && totalTargets > 0
                && successCount == totalTargets
                && failedCount == 0
                && skippedCount == 0;
    }

    private static int defaultTotalTargets(ProviderSyncStatus status) {
        return switch (status) {
            case SUCCESS -> 1;
            case PARTIAL_SUCCESS -> 2;
            case FAILED -> 0;
        };
    }

    private static int defaultSuccessCount(ProviderSyncStatus status) {
        return switch (status) {
            case SUCCESS, PARTIAL_SUCCESS -> 1;
            case FAILED -> 0;
        };
    }

    private static int defaultFailedCount(ProviderSyncStatus status) {
        return status == ProviderSyncStatus.PARTIAL_SUCCESS ? 1 : 0;
    }
}
