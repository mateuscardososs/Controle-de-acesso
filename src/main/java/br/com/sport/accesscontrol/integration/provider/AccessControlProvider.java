package br.com.sport.accesscontrol.integration.provider;

import java.util.Map;
import java.util.UUID;

public interface AccessControlProvider {

    ProviderSyncResult syncPerson(ProviderPerson person);

    ProviderSyncResult removePerson(ProviderPerson person);

    ProviderSyncResult updatePermission(ProviderPermission permission);

    ProviderDeviceStatus fetchDeviceStatus(UUID deviceId);

    NormalizedAccessEvent normalizeAccessEvent(Map<String, Object> payload);
}
