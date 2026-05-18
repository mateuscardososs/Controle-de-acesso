package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.guests.GuestDtos.GuestResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.PublicGuestRegistrationResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/guest-registration")
public class PublicGuestRegistrationController {

    private final GuestService guestService;

    public PublicGuestRegistrationController(GuestService guestService) {
        this.guestService = guestService;
    }

    @GetMapping("/{token}")
    PublicGuestRegistrationResponse get(@PathVariable String token) {
        return guestService.publicRegistration(token);
    }

    @PostMapping("/{token}/complete")
    GuestResponse complete(@PathVariable String token,
                           @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String company,
                           @RequestPart("facePhoto") MultipartFile facePhoto) {
        return guestService.completeRegistration(token, phone, company, facePhoto);
    }
}
