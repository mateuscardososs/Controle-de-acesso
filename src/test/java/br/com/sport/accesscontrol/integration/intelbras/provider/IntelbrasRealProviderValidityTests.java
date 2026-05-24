package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelbrasRealProviderValidityTests {

    @Test
    void sendsValidityConvertedWithConfiguredTimezone() {
        var properties = new IntelbrasProperties();
        properties.setTimezone("America/Recife");
        var device = device();
        var connection = new IntelbrasDeviceConnection(device, "192.168.15.5", "admin", "secret");
        var connectionService = mock(IntelbrasDeviceConnectionService.class);
        var cgiClient = mock(IntelbrasCgiClient.class);
        when(connectionService.selectOnlineConfiguredDevice(any())).thenReturn(Optional.of(connection));
        var provider = new IntelbrasRealProvider(
                connectionService,
                cgiClient,
                mock(IntelbrasFaceEncoder.class),
                new IntelbrasEventMapper(properties),
                properties,
                new AccessMetricsService(new SimpleMeterRegistry())
        );
        var validFrom = Instant.parse("2026-06-10T18:00:00Z");
        var validUntil = Instant.parse("2026-06-11T07:00:00Z");
        var person = new ProviderPerson(PersonType.GUEST, UUID.randomUUID(), "12345678901", "Visitante",
                null, true, validFrom, validUntil);
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var untilCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        provider.syncPerson(person);

        verify(cgiClient).upsertAccessUser(
                eq("192.168.15.5"),
                eq("admin"),
                eq("secret"),
                anyString(),
                anyString(),
                eq("Visitante"),
                fromCaptor.capture(),
                untilCaptor.capture()
        );
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 10, 15, 0));
        assertThat(untilCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 11, 4, 0));
    }

    private Device device() {
        var area = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca 1", "Intelbras SS 5531 MF W", "DRWL3903457HU", "192.168.15.5",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        return device;
    }
}
