package br.com.sport.accesscontrol.integration.provider;

import java.util.Map;
import java.util.UUID;

public interface AccessControlProvider {

    void syncPerson(ProviderPerson person);

    void removePerson(ProviderPerson person);

    void updatePermission(ProviderPermission permission);

    ProviderDeviceStatus fetchDeviceStatus(UUID deviceId);

    NormalizedAccessEvent normalizeAccessEvent(Map<String, Object> payload);
}
