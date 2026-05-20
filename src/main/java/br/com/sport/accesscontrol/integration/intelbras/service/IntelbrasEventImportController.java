package br.com.sport.accesscontrol.integration.intelbras.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/intelbras")
public class IntelbrasEventImportController {

    private final IntelbrasEventImportService importService;

    public IntelbrasEventImportController(IntelbrasEventImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/devices/{deviceId}/events/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IntelbrasEventImportResult importEvents(@PathVariable UUID deviceId) {
        return importService.importAccessControlEvents(deviceId);
    }

    @PostMapping("/events/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IntelbrasEventImportResult importEventsNow() {
        return importService.importOnlineAccessControlEvents();
    }
}
