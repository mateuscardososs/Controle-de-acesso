package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.areas.AreaService;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.common.events.DeviceStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final AreaService areaService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public DeviceService(DeviceRepository deviceRepository, AreaService areaService,
                         ApplicationEventPublisher eventPublisher, AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.areaService = areaService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public DeviceResponse create(DeviceRequest request) {
        var area = areaService.getById(request.areaId());
        var device = new Device(
                request.name(),
                request.model(),
                request.serialNumber(),
                request.ipAddress(),
                request.location(),
                request.operationType(),
                request.status(),
                area
        );
        var saved = deviceRepository.save(device);
        auditService.record("DEVICE_CREATED", "Device", saved.getId(), Map.of("ipAddress", saved.getIpAddress()),
                Map.of(), deviceSnapshot(saved));
        return DeviceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> findAll() {
        return deviceRepository.findAll().stream().map(DeviceResponse::from).toList();
    }

    @Transactional
    public DeviceResponse updateStatus(UUID id, DeviceStatusRequest request) {
        var device = getById(id);
        var oldData = deviceSnapshot(device);
        device.setStatus(request.status());
        auditService.record("DEVICE_STATUS_CHANGED", "Device", device.getId(), Map.of("status", request.status()),
                oldData, deviceSnapshot(device));
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(device.getId(), device.getStatus()));
        return DeviceResponse.from(device);
    }

    @Transactional(readOnly = true)
    public Device getById(UUID id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Device> findOnlineDevices() {
        return deviceRepository.findByOnlineStatus(DeviceStatus.ONLINE);
    }

    private Map<String, Object> deviceSnapshot(Device device) {
        return Map.of(
                "id", device.getId(),
                "name", device.getName(),
                "status", device.getStatus(),
                "onlineStatus", device.getOnlineStatus(),
                "communicationFailures", device.getCommunicationFailures()
        );
    }
}
