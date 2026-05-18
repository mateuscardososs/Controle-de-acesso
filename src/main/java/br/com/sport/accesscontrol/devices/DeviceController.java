package br.com.sport.accesscontrol.devices;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
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

    @PatchMapping("/{id}/status")
    DeviceResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody DeviceStatusRequest request) {
        return deviceService.updateStatus(id, request);
    }
}
