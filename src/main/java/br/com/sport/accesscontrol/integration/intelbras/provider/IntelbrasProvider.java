package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.integration.provider.ProviderDeviceStatus;
import br.com.sport.accesscontrol.integration.provider.ProviderPermission;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(prefix = "app.intelbras", name = "mode", havingValue = "fake", matchIfMissing = true)
public class IntelbrasProvider implements AccessControlProvider {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasProvider.class);

    @Override
    public ProviderSyncResult syncPerson(ProviderPerson person) {
        var start = Instant.now();
        simulateLatency();
        log.info("intelbras_provider_sync_person simulated=true person_type={} person_id={} document={}",
                person.personType(), person.personId(), person.document());
        return simulatedResult(start);
    }

    @Override
    public ProviderSyncResult removePerson(ProviderPerson person) {
        var start = Instant.now();
        simulateLatency();
        log.info("intelbras_provider_remove_person simulated=true person_type={} person_id={} document={}",
                person.personType(), person.personId(), person.document());
        return simulatedResult(start);
    }

    @Override
    public ProviderSyncResult updatePermission(ProviderPermission permission) {
        var start = Instant.now();
        simulateLatency();
        log.info("intelbras_provider_update_permission simulated=true person_type={} person_id={} area_id={} active={}",
                permission.personType(), permission.personId(), permission.areaId(), permission.active());
        return simulatedResult(start);
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

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(40, 180));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ProviderSyncResult simulatedResult(Instant start) {
        int value = ThreadLocalRandom.current().nextInt(100);
        var latency = Duration.between(start, Instant.now());
        if (value < 8) {
            return new ProviderSyncResult(ProviderSyncStatus.FAILED, "Simulated Intelbras communication failure", latency, 1, 0, 1, 0);
        }
        if (value < 18) {
            return new ProviderSyncResult(ProviderSyncStatus.PARTIAL_SUCCESS, "Simulated partial sync: permissions pending", latency, 2, 1, 1, 0);
        }
        return new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "Simulated sync success", latency, 1, 1, 0, 0);
    }
}
