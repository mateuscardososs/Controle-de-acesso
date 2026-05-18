package br.com.sport.accesscontrol.integration.intelbras.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/integration/intelbras/webhooks")
public class IntelbrasWebhookController {

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> receivePreparedWebhook(@RequestBody Map<String, Object> payload) {
        return Map.of("status", "accepted", "mode", "prepared");
    }
}
