package br.com.sport.accesscontrol.integration.sync;

import java.util.UUID;

public record GuestReadyForSyncEvent(UUID guestId) {
}
