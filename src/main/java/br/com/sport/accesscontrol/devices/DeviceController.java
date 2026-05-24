package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final IntelbrasIntegrationService integrationService;

    public DeviceController(DeviceService deviceService, IntelbrasIntegrationService integrationService) {
        this.deviceService = deviceService;
        this.integrationService = integrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeviceResponse create(@Valid @RequestBody DeviceRequest request) {
        return deviceService.create(request);
    }

    @GetMapping
    List<DeviceResponse> findAll() {
        return deviceService.findAll();
    }

    @GetMapping("/status")
    List<DeviceStatusResponse> findStatuses() {
        return deviceService.findStatuses();
    }

    @PutMapping("/{id}")
    DeviceResponse update(@PathVariable UUID id, @Valid @RequestBody DeviceRequest request) {
        return deviceService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        deviceService.delete(id);
    }

    @PostMapping("/{id}/ping")
    DeviceResponse ping(@PathVariable UUID id) {
        var device = deviceService.getById(id);
        integrationService.synchronizeDevice(device);
        return DeviceResponse.from(deviceService.getById(id));
    }

    @PatchMapping("/{id}/status")
    DeviceResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody DeviceStatusRequest request) {
        return deviceService.updateStatus(id, request);
    }
}
