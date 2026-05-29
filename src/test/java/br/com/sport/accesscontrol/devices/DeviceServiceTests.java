package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.areas.AreaService;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTests {

    @Test
    void deleteDeviceWithLinkedEventsSoftDeletesAndPreservesHistory() {
        var device = device();
        var repository = mock(DeviceRepository.class);
        var accessEvents = mock(AccessEventRepository.class);
        var realtime = mock(RealtimePublisherService.class);
        when(repository.findById(device.getId())).thenReturn(Optional.of(device));
        when(accessEvents.existsByDevice_Id(device.getId())).thenReturn(true);
        var service = new DeviceService(repository, mock(AreaService.class), mock(ApplicationEventPublisher.class),
                mock(AuditService.class), realtime, accessEvents);

        var response = service.delete(device.getId());

        assertThat(response.deactivated()).isTrue();
        assertThat(response.removed()).isFalse();
        assertThat(response.message()).isEqualTo("Não foi possível remover porque existem eventos vinculados; dispositivo foi desativado");
        assertThat(device.isActive()).isFalse();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        verify(repository).save(device);
        verify(repository, never()).delete(device);
        verify(realtime).publishDeviceStatus(eq(device), eq(response.message()));
    }

    @Test
    void inactiveDevicesAreHiddenFromListsAndOnlineLookup() {
        var active = device();
        active.setStatus(DeviceStatus.ONLINE);
        var inactive = device();
        inactive.setStatus(DeviceStatus.ONLINE);
        inactive.deactivate();
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(active, inactive));
        when(repository.findByOnlineStatus(DeviceStatus.ONLINE)).thenReturn(List.of(active, inactive));
        var service = new DeviceService(repository, mock(AreaService.class), mock(ApplicationEventPublisher.class),
                mock(AuditService.class), mock(RealtimePublisherService.class));

        assertThat(service.findAll()).extracting(DeviceResponse::id).containsExactly(active.getId());
        assertThat(service.findOnlineDevices()).extracting(Device::getId).containsExactly(active.getId());
    }

    private Device device() {
        var area = new Area("Portaria", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca", "Intelbras SS 5531 MF W", "DRWL3903457HU", "192.168.15.5",
                "Portaria", DeviceOperationType.ENTRY_EXIT, DeviceStatus.UNKNOWN, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        return device;
    }
}
