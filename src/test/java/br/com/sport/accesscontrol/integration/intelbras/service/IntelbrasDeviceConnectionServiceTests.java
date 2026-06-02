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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void rejectsInactiveDeviceFromSyncAndImportCandidates() {
        var device = device();
        device.setIntelbrasUsername("admin");
        device.setIntelbrasPassword("secret");
        device.deactivate();
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        assertThat(service.selectOnlineConfiguredDevice(device.getArea().getId())).isEmpty();
        assertThat(service.onlineConfiguredDevices()).isEmpty();
        assertThat(service.selectOnlineConfiguredDevicesForAreas(Set.of(device.getArea().getId()))).isEmpty();
    }

    @Test
    void eligibleWhenDeviceHasPasswordButGenericNameAndModel() {
        var area = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca Social", "Generic Controller", "SN999", "192.168.15.10",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        device.setIntelbrasUsername("admin");
        device.setIntelbrasPassword("secret");
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));
        when(repository.findById(device.getId())).thenReturn(java.util.Optional.of(device));
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        assertThat(service.onlineConfiguredDevices()).hasSize(1);
        assertThat(service.selectOnlineConfiguredDevice(area.getId())).isPresent();
    }

    @Test
    void rejectedWhenNoPasswordAndGenericNameAndModel() {
        var area = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca Social", "Generic Controller", "SN999", "192.168.15.10",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        device.setIntelbrasUsername("admin");
        // no password — must be rejected regardless of other fields
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        assertThat(service.onlineConfiguredDevices()).isEmpty();
        assertThat(service.selectOnlineConfiguredDevice(area.getId())).isEmpty();
    }

    @Test
    void selectOnlineForAreasFiltersToMatchingAreasOnly() {
        var portariaArea = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(portariaArea, "id", UUID.randomUUID());
        var front1Area = new Area("Front 1", "Front 1", true);
        ReflectionTestUtils.setField(front1Area, "id", UUID.randomUUID());

        var portariaDevice = new Device("Catraca Entrada", "Generic Controller", "SN001", "192.168.15.5",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, portariaArea);
        ReflectionTestUtils.setField(portariaDevice, "id", UUID.randomUUID());
        portariaDevice.setIntelbrasUsername("admin");
        portariaDevice.setIntelbrasPassword("secret");

        var front1Device = new Device("Catraca Social", "Generic Controller", "SN002", "192.168.15.6",
                "Front 1", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, front1Area);
        ReflectionTestUtils.setField(front1Device, "id", UUID.randomUUID());
        front1Device.setIntelbrasUsername("admin");
        front1Device.setIntelbrasPassword("secret");

        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(portariaDevice, front1Device));
        when(repository.findById(portariaDevice.getId())).thenReturn(java.util.Optional.of(portariaDevice));
        when(repository.findById(front1Device.getId())).thenReturn(java.util.Optional.of(front1Device));
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        var portariaOnly = service.selectOnlineConfiguredDevicesForAreas(Set.of(portariaArea.getId()));
        assertThat(portariaOnly).hasSize(1);
        assertThat(portariaOnly.get(0).device().getArea().getName()).isEqualTo("Portaria");

        var both = service.selectOnlineConfiguredDevicesForAreas(Set.of(portariaArea.getId(), front1Area.getId()));
        assertThat(both).hasSize(2);

        var noMatch = service.selectOnlineConfiguredDevicesForAreas(Set.of(UUID.randomUUID()));
        assertThat(noMatch).isEmpty();
    }

    @Test
    void selectOnlineForAreasUsesDevicesFetchedWithArea() {
        var portaria = area("Portaria");
        var device = device(portaria, "192.168.50.101");
        var repository = mock(DeviceRepository.class);
        when(repository.findAllWithArea()).thenReturn(List.of(device));
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        var selected = service.selectOnlineConfiguredDevicesForAreas(Set.of(portaria.getId()));

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().device().getArea().getName()).isEqualTo("Portaria");
        verify(repository).findAllWithArea();
        verify(repository, never()).findAll();
    }

    @Test
    void employeeFullAccessAreaSetSelectsAllTenEligibleControllers() {
        var portaria = area("Portaria");
        var front1 = area("Front 1");
        var front2 = area("Front 2");
        var institucional1 = area("Institucional 1");
        var vereadores = area("Institucional Vereadores");
        var devices = List.of(
                device(portaria, "192.168.50.101"),
                device(portaria, "192.168.50.102"),
                device(portaria, "192.168.50.103"),
                device(portaria, "192.168.50.104"),
                device(front1, "192.168.50.109"),
                device(front2, "192.168.50.111"),
                device(institucional1, "192.168.50.105"),
                device(institucional1, "192.168.50.106"),
                device(vereadores, "192.168.50.107"),
                device(vereadores, "192.168.50.108")
        );
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(devices);
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        var selected = service.selectOnlineConfiguredDevicesForAreas(Set.of(
                portaria.getId(), front1.getId(), front2.getId(), institucional1.getId(), vereadores.getId()
        ));

        assertThat(selected).hasSize(10);
        assertThat(selected).extracting(connection -> connection.device().getIpAddress())
                .containsExactlyInAnyOrder(
                        "192.168.50.101", "192.168.50.102", "192.168.50.103", "192.168.50.104",
                        "192.168.50.109", "192.168.50.111", "192.168.50.105", "192.168.50.106",
                        "192.168.50.107", "192.168.50.108"
                );
    }

    @Test
    void guestFront1SelectsFourPortariaControllersAndOneFront1Controller() {
        var portaria = area("Portaria");
        var front1 = area("Front 1");
        var front2 = area("Front 2");
        var devices = List.of(
                device(portaria, "192.168.50.101"),
                device(portaria, "192.168.50.102"),
                device(portaria, "192.168.50.103"),
                device(portaria, "192.168.50.104"),
                device(front1, "192.168.50.109"),
                device(front2, "192.168.50.111")
        );
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(devices);
        var service = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties());

        var selected = service.selectOnlineConfiguredDevicesForAreas(Set.of(portaria.getId(), front1.getId()));

        assertThat(selected).hasSize(5);
        assertThat(selected).extracting(connection -> connection.device().getIpAddress())
                .containsExactlyInAnyOrder(
                        "192.168.50.101",
                        "192.168.50.102",
                        "192.168.50.103",
                        "192.168.50.104",
                        "192.168.50.109"
                );
    }

    private Device device() {
        var area = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca 1", "Intelbras SS 5531 MF W", "DRWL3903457HU", "192.168.15.5",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        return device;
    }

    private Area area(String name) {
        var area = new Area(name, name, true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }

    private Device device(Area area, String ip) {
        var device = new Device("Catraca " + ip, "Intelbras SS 5531 MF W", "SN-" + ip, ip,
                area.getName(), DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        device.setIntelbrasUsername("admin");
        device.setIntelbrasPassword("secret");
        return device;
    }
}
