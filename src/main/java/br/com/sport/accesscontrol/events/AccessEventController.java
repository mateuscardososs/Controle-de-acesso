package br.com.sport.accesscontrol.events;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/access-events")
public class AccessEventController {

    private final AccessEventService accessEventService;

    public AccessEventController(AccessEventService accessEventService) {
        this.accessEventService = accessEventService;
    }

    @PostMapping("/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    AccessEventResponse simulate(@Valid @RequestBody AccessEventSimulationRequest request) {
        return accessEventService.simulate(request);
    }

    @GetMapping
    List<AccessEventResponse> findAll() {
        return accessEventService.findAll();
    }
}
