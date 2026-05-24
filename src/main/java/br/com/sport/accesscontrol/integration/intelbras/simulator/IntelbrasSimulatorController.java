package br.com.sport.accesscontrol.integration.intelbras.simulator;

import br.com.sport.accesscontrol.events.AccessEventResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulator")
@ConditionalOnProperty(name = "app.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class IntelbrasSimulatorController {

    private final IntelbrasSimulatorService simulatorService;

    public IntelbrasSimulatorController(IntelbrasSimulatorService simulatorService) {
        this.simulatorService = simulatorService;
    }

    @PostMapping("/access-event")
    @ResponseStatus(HttpStatus.CREATED)
    AccessEventResponse simulate(@Valid @RequestBody IntelbrasAccessEventSimulatorRequest request) {
        return simulatorService.simulate(request);
    }
}
