package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.guests.GuestDtos.PublicVisitorRegistrationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@RestController
@RequestMapping("/api/public")
public class PublicVisitorRegistrationController {

    private final GuestService guestService;

    public PublicVisitorRegistrationController(GuestService guestService) {
        this.guestService = guestService;
    }

    @PostMapping("/visitor-registration")
    @ResponseStatus(HttpStatus.CREATED)
    PublicVisitorRegistrationResponse register(
            @RequestParam String fullName,
            @RequestParam String cpf,
            @RequestParam String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String company,
            @RequestParam String visitReason,
            @RequestParam String hostName,
            @RequestParam Instant visitStart,
            @RequestParam Instant visitEnd,
            @RequestPart(required = false) MultipartFile facePhoto
    ) {
        return guestService.publicVisitorRegistration(
                fullName, cpf, email, phone, company, visitReason, hostName, visitStart, visitEnd, facePhoto
        );
    }
}
