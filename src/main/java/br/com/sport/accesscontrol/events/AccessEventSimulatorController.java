package br.com.sport.accesscontrol.events;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/access-events")
@ConditionalOnProperty(name = "app.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class AccessEventSimulatorController {

    private final AccessEventService accessEventService;

    public AccessEventSimulatorController(AccessEventService accessEventService) {
        this.accessEventService = accessEventService;
    }

    @PostMapping("/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    AccessEventResponse simulate(@Valid @RequestBody AccessEventSimulationRequest request) {
        return accessEventService.simulate(request);
    }
}
