package br.com.sport.accesscontrol.events;

import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/access-events")
public class AccessEventController {

    private final AccessEventService accessEventService;

    public AccessEventController(AccessEventService accessEventService) {
        this.accessEventService = accessEventService;
    }

    @GetMapping
    Object findAll(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String personCpf,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invitedDay,
            @RequestParam(required = false) String invitedLounge,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) AccessEventType eventType,
            @RequestParam(required = false) AccessResult accessResult,
            @RequestParam(required = false) RecognitionStatus recognitionStatus,
            @RequestParam(required = false) PassageStatus passageStatus,
            @RequestParam(required = false) ReleaseMethod releaseMethod,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) Boolean manualOnly
    ) {
        return accessEventService.search(new AccessEventSearchRequest(
                page == null ? 0 : page,
                size == null ? 50 : size,
                startDate,
                endDate,
                personName,
                personCpf,
                invitedDay,
                invitedLounge,
                deviceId,
                areaId,
                eventType,
                accessResult,
                recognitionStatus,
                passageStatus,
                releaseMethod,
                origin,
                manualOnly
        ));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String personCpf,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invitedDay,
            @RequestParam(required = false) String invitedLounge,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) AccessEventType eventType,
            @RequestParam(required = false) AccessResult accessResult,
            @RequestParam(required = false) RecognitionStatus recognitionStatus,
            @RequestParam(required = false) PassageStatus passageStatus,
            @RequestParam(required = false) ReleaseMethod releaseMethod,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) Boolean manualOnly,
            @RequestParam(required = false) Integer size
    ) {
        var csv = accessEventService.exportCsv(new AccessEventSearchRequest(
                0,
                size == null ? 10_000 : size,
                startDate,
                endDate,
                personName,
                personCpf,
                invitedDay,
                invitedLounge,
                deviceId,
                areaId,
                eventType,
                accessResult,
                recognitionStatus,
                passageStatus,
                releaseMethod,
                origin,
                manualOnly
        ));
        var filename = "eventos-acesso-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/manual-release")
    @ResponseStatus(HttpStatus.CREATED)
    AccessEventResponse manualRelease(@Valid @RequestBody ManualAccessReleaseRequest request, Authentication authentication) {
        return accessEventService.manualRelease(request, authentication);
    }

    List<AccessEventResponse> legacyFindAll() {
        return accessEventService.findAll();
    }
}
