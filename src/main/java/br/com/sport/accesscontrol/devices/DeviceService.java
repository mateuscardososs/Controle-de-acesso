package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.areas.AreaService;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.common.events.DeviceStatusChangedEvent;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
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
    private final RealtimePublisherService realtimePublisherService;
    private final AccessEventRepository accessEventRepository;

    public DeviceService(DeviceRepository deviceRepository, AreaService areaService,
                         ApplicationEventPublisher eventPublisher, AuditService auditService,
                         RealtimePublisherService realtimePublisherService) {
        this(deviceRepository, areaService, eventPublisher, auditService, realtimePublisherService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceService(DeviceRepository deviceRepository, AreaService areaService,
                         ApplicationEventPublisher eventPublisher, AuditService auditService,
                         RealtimePublisherService realtimePublisherService,
                         AccessEventRepository accessEventRepository) {
        this.deviceRepository = deviceRepository;
        this.areaService = areaService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
        this.accessEventRepository = accessEventRepository;
    }

    @Transactional
    public DeviceResponse create(DeviceRequest request) {
        var area = areaService.getById(request.areaId());
        var device = new Device(
                request.name(),
                request.model(),
                request.serialNumber(),
                request.ipAddress(),
                request.httpPort(),
                request.location(),
                request.operationType(),
                request.status(),
                area
        );
        device.setIntelbrasUsername(request.intelbrasUsername());
        device.setIntelbrasPassword(request.intelbrasPassword());
        var saved = deviceRepository.save(device);
        auditService.record("DEVICE_CREATED", "Device", saved.getId(), Map.of("ipAddress", saved.getIpAddress()),
                Map.of(), deviceSnapshot(saved));
        return DeviceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> findAll() {
        return activeDevices().stream().map(DeviceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DeviceStatusResponse> findStatuses() {
        return activeDevices().stream().map(DeviceStatusResponse::from).toList();
    }

    @Transactional
    public DeviceResponse updateStatus(UUID id, DeviceStatusRequest request) {
        var device = getById(id);
        var oldData = deviceSnapshot(device);
        device.setStatus(request.status());
        auditService.record("DEVICE_STATUS_CHANGED", "Device", device.getId(), Map.of("status", request.status()),
                oldData, deviceSnapshot(device));
        realtimePublisherService.publishDeviceStatus(device, "Device status changed");
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(device.getId(), device.getStatus()));
        return DeviceResponse.from(device);
    }

    @Transactional(readOnly = true)
    public Device getById(UUID id) {
        return deviceRepository.findById(id)
                .filter(Device::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + id));
    }

    @Transactional
    public DeviceResponse update(UUID id, DeviceRequest request) {
        var device = getById(id);
        var area = areaService.getById(request.areaId());
        var oldData = deviceSnapshot(device);
        device.update(
                request.name(),
                request.model(),
                request.serialNumber(),
                request.ipAddress(),
                request.httpPort(),
                request.location(),
                request.operationType(),
                request.status(),
                area
        );
        device.setIntelbrasUsername(request.intelbrasUsername());
        if (request.intelbrasPassword() != null && !request.intelbrasPassword().isBlank()) {
            device.setIntelbrasPassword(request.intelbrasPassword());
        }
        auditService.record("DEVICE_UPDATED", "Device", device.getId(), Map.of("ipAddress", device.getIpAddress()),
                oldData, deviceSnapshot(device));
        realtimePublisherService.publishDeviceStatus(device, "Device updated");
        return DeviceResponse.from(device);
    }

    @Transactional
    public DeviceDeleteResponse delete(UUID id) {
        var device = getById(id);
        var oldData = deviceSnapshot(device);
        var hasLinkedEvents = accessEventRepository != null && accessEventRepository.existsByDevice_Id(id);
        device.deactivate();
        deviceRepository.save(device);
        var message = hasLinkedEvents
                ? "Não foi possível remover porque existem eventos vinculados; dispositivo foi desativado"
                : "Dispositivo removido";
        auditService.record(hasLinkedEvents ? "DEVICE_DEACTIVATED" : "DEVICE_REMOVED", "Device", device.getId(),
                Map.of("name", device.getName(), "ipAddress", String.valueOf(device.getIpAddress()), "message", message),
                oldData, deviceSnapshot(device));
        realtimePublisherService.publishDeviceStatus(device, message);
        return new DeviceDeleteResponse(false, true, message, DeviceResponse.from(device));
    }

    @Transactional(readOnly = true)
    public List<Device> findOnlineDevices() {
        return deviceRepository.findByOnlineStatus(DeviceStatus.ONLINE).stream()
                .filter(Device::isActive)
                .toList();
    }

    private List<Device> activeDevices() {
        return deviceRepository.findAll().stream()
                .filter(Device::isActive)
                .toList();
    }

    private Map<String, Object> deviceSnapshot(Device device) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("id", device.getId());
        snapshot.put("name", device.getName());
        snapshot.put("active", device.isActive());
        snapshot.put("status", device.getStatus());
        snapshot.put("onlineStatus", device.getOnlineStatus());
        snapshot.put("lastSuccessAt", device.getLastSuccessAt());
        snapshot.put("lastFailureAt", device.getLastFailureAt());
        snapshot.put("communicationFailures", device.getCommunicationFailures());
        return snapshot;
    }
}
