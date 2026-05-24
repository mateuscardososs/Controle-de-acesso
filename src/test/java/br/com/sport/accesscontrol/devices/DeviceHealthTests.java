package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceHealthTests {

    private DeviceRepository deviceRepository;
    private DeviceHealthService deviceHealthService;
    private Device device;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository = mock(DeviceRepository.class);
        var areaService = mock(br.com.sport.accesscontrol.areas.AreaService.class);
        var eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var metricsService = new AccessMetricsService(new SimpleMeterRegistry());
        var deviceService = new DeviceService(deviceRepository, areaService, eventPublisher, auditService, realtimePublisher);
        deviceHealthService = new DeviceHealthService(deviceService, auditService, realtimePublisher, metricsService);

        var area = mock(Area.class);
        when(area.getId()).thenReturn(UUID.randomUUID());
        when(area.getName()).thenReturn("Área Test");
        device = new Device("Catraca Test", "Intelbras SS 5531 MF W", null,
                "192.168.1.10", 80, "Portaria", DeviceOperationType.ENTRY_EXIT, DeviceStatus.UNKNOWN, area);
        device.setIntelbrasUsername("admin");
        device.setIntelbrasPassword("senha123");
        var idField = Device.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(device, UUID.randomUUID());
    }

    private void stubDevice() {
        when(deviceRepository.findById(any())).thenReturn(Optional.of(device));
    }

    @Test
    void heartbeatMarkasDeviceOnline() {
        stubDevice();
        deviceHealthService.heartbeat(UUID.randomUUID());

        assertThat(device.getOnlineStatus()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(device.getCommunicationFailures()).isZero();
        assertThat(device.getLastError()).isNull();
        assertThat(device.getLastHeartbeatAt()).isNotNull();
        assertThat(device.getLastSuccessAt()).isNotNull();
    }

    @Test
    void communicationFailureBelowThresholdDoesNotGoOffline() {
        stubDevice();
        deviceHealthService.communicationFailure(UUID.randomUUID(), "connection refused");

        assertThat(device.getCommunicationFailures()).isEqualTo(1);
        assertThat(device.getLastError()).isEqualTo("connection refused");
        assertThat(device.getStatus()).isNotEqualTo(DeviceStatus.OFFLINE);
    }

    @Test
    void threeConsecutiveFailuresMarksDeviceOffline() {
        stubDevice();
        var id = UUID.randomUUID();
        deviceHealthService.communicationFailure(id, "timeout");
        deviceHealthService.communicationFailure(id, "timeout");
        deviceHealthService.communicationFailure(id, "timeout");

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(device.getOnlineStatus()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(device.getCommunicationFailures()).isEqualTo(3);
        assertThat(device.getLastError()).isEqualTo("timeout");
    }

    @Test
    void heartbeatAfterOfflineRestoresOnline() {
        stubDevice();
        var id = UUID.randomUUID();
        deviceHealthService.communicationFailure(id, "timeout");
        deviceHealthService.communicationFailure(id, "timeout");
        deviceHealthService.communicationFailure(id, "timeout");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.OFFLINE);

        deviceHealthService.heartbeat(id);

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(device.getCommunicationFailures()).isZero();
        assertThat(device.getLastError()).isNull();
    }
}
