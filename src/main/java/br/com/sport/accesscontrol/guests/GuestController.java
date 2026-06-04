package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.guests.GuestDtos.GuestRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestResponse;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportPreviewResponse;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportReport;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/guests")
public class GuestController {

    private static final Logger log = LoggerFactory.getLogger(GuestController.class);
    private final GuestService guestService;
    private final GuestImportService guestImportService;

    public GuestController(GuestService guestService, GuestImportService guestImportService) {
        this.guestService = guestService;
        this.guestImportService = guestImportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GuestResponse create(@Valid @RequestBody GuestRequest request) {
        return guestService.create(request);
    }

    @PostMapping(value = "/visitor-registration", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    GuestResponse createVisitorRegistration(
            @RequestParam String fullName,
            @RequestParam String cpf,
            @RequestParam(required = false) String email,
            @RequestParam String phone,
            @RequestParam LocalDate invitedDay,
            @RequestParam String invitedLounge,
            @RequestPart(name = "facePhoto", required = false) MultipartFile facePhoto
    ) {
        return guestService.adminVisitorRegistration(fullName, cpf, email, phone, invitedDay, invitedLounge, facePhoto);
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportPreviewResponse previewImport(@RequestPart("file") MultipartFile file) throws IOException {
        return guestImportService.preview(file);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importGuests(@RequestPart("file") MultipartFile file) throws IOException {
        return guestImportService.importFile(file);
    }

    @GetMapping("/import/template")
    ResponseEntity<byte[]> importTemplate() {
        var content = guestImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("modelo-importacao-convidados.xlsx")
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @GetMapping
    List<GuestResponse> findAll(@RequestParam(required = false) String scope) {
        return "today".equals(scope) ? guestService.today() : guestService.findAll();
    }

    @GetMapping("/{id}")
    GuestResponse get(@PathVariable UUID id) {
        return guestService.get(id);
    }

    @PutMapping("/{id}")
    GuestResponse update(@PathVariable UUID id, @Valid @RequestBody GuestRequest request) {
        return guestService.update(id, request);
    }

    @PatchMapping("/{id}/cancel")
    GuestResponse cancel(@PathVariable UUID id) {
        return guestService.cancel(id);
    }

    @PostMapping("/{id}/resend-invite")
    GuestResponse resendInvite(@PathVariable UUID id) {
        return guestService.resendInvite(id);
    }

    @PostMapping("/{id}/sync")
    GuestResponse sync(@PathVariable UUID id) {
        log.info("GUEST_SYNC_REQUEST_RECEIVED person_type=GUEST person_id={}", id);
        log.info("SYNC_REQUEST_RECEIVED person_type=GUEST person_id={}", id);
        return guestService.requestSync(id);
    }

    @DeleteMapping("/cleanup")
    GuestCleanupResponse cleanup(@Valid @RequestBody GuestCleanupRequest request) {
        return guestService.cleanup(request);
    }

    @PostMapping("/{id}/complete-registration")
    GuestResponse completeRegistration(@PathVariable UUID id, @RequestParam String token,
                                       @RequestParam(required = false) String phone,
                                       @RequestParam(required = false) String company,
                                       @RequestPart(name = "facePhoto", required = false) MultipartFile facePhoto) {
        var response = guestService.completeRegistration(token, phone, company, facePhoto);
        if (!response.id().equals(id)) {
            throw new IllegalArgumentException("Invite token does not belong to guest.");
        }
        return response;
    }
}
