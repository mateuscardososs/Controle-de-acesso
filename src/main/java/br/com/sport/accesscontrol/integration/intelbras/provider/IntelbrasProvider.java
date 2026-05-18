package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.integration.provider.ProviderDeviceStatus;
import br.com.sport.accesscontrol.integration.provider.ProviderPermission;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class IntelbrasProvider implements AccessControlProvider {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasProvider.class);

    @Override
    public void syncPerson(ProviderPerson person) {
        log.info("intelbras_provider_sync_person simulated=true person_type={} person_id={} document={}",
                person.personType(), person.personId(), person.document());
    }

    @Override
    public void removePerson(ProviderPerson person) {
        log.info("intelbras_provider_remove_person simulated=true person_type={} person_id={} document={}",
                person.personType(), person.personId(), person.document());
    }

    @Override
    public void updatePermission(ProviderPermission permission) {
        log.info("intelbras_provider_update_permission simulated=true person_type={} person_id={} area_id={} active={}",
                permission.personType(), permission.personId(), permission.areaId(), permission.active());
    }

    @Override
    public ProviderDeviceStatus fetchDeviceStatus(UUID deviceId) {
        log.info("intelbras_provider_fetch_device_status simulated=true device_id={}", deviceId);
        return new ProviderDeviceStatus(deviceId, DeviceStatus.UNKNOWN, Instant.now(), "Simulated provider status");
    }

    @Override
    public NormalizedAccessEvent normalizeAccessEvent(Map<String, Object> payload) {
        log.info("intelbras_provider_normalize_access_event simulated=true");
        throw new UnsupportedOperationException("Intelbras event normalization is prepared but not implemented yet.");
    }
}
