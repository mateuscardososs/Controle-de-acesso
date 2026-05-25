package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelbrasDeviceConnectionServiceTests {

    @Test
    void rejectsDeviceWithoutOwnPasswordEvenWhenDefaultPasswordIsConfigured() {
        var device = device();
        device.setIntelbrasUsername("admin");
        var properties = new IntelbrasProperties();
        properties.setDefaultPassword("admin123");
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));
        when(repository.findById(device.getId())).thenReturn(java.util.Optional.of(device));
        var service = new IntelbrasDeviceConnectionService(repository, properties);

        assertThat(service.selectOnlineConfiguredDevice(device.getArea().getId())).isEmpty();
        assertThat(service.connectionFor(device).password()).isNull();
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
