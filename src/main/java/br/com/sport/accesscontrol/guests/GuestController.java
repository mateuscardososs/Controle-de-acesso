package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.guests.GuestDtos.GuestRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/guests")
public class GuestController {

    private final GuestService guestService;

    public GuestController(GuestService guestService) {
        this.guestService = guestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GuestResponse create(@Valid @RequestBody GuestRequest request) {
        return guestService.create(request);
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

    @PostMapping("/{id}/complete-registration")
    GuestResponse completeRegistration(@PathVariable UUID id, @RequestParam String token,
                                       @RequestParam(required = false) String phone,
                                       @RequestParam(required = false) String company,
                                       @RequestPart("facePhoto") MultipartFile facePhoto) {
        var response = guestService.completeRegistration(token, phone, company, facePhoto);
        if (!response.id().equals(id)) {
            throw new IllegalArgumentException("Invite token does not belong to guest.");
        }
        return response;
    }
}
