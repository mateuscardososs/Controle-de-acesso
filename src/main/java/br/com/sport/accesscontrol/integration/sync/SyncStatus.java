package br.com.sport.accesscontrol.integration.sync;

public enum SyncStatus {
    NOT_REQUIRED,
    PENDING_SYNC,
    SYNCING,
    SYNCED_WITH_WARNINGS,
    SYNCED,
    SYNC_FAILED
}
