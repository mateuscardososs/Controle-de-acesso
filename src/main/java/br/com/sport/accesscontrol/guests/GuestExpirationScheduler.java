package br.com.sport.accesscontrol.guests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GuestExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(GuestExpirationScheduler.class);
    private final GuestService guestService;

    public GuestExpirationScheduler(GuestService guestService) {
        this.guestService = guestService;
    }

    @Scheduled(fixedDelayString = "${app.guests.expiration-scan-delay-ms:300000}")
    public void expireOverdueGuests() {
        var expired = guestService.expireOverdueGuests();
        if (!expired.isEmpty()) {
            log.info("guest_expiration_scan expired_count={}", expired.size());
        }
    }
}
