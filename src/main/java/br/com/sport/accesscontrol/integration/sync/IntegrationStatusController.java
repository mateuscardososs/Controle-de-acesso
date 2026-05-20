package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integration/intelbras")
public class IntegrationStatusController {

    private final IntelbrasProperties properties;

    public IntegrationStatusController(IntelbrasProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        var mode = properties.getMode().name().toLowerCase();
        return Map.of(
                "mode", mode,
                "syncEnabled", properties.getMode() == IntelbrasProperties.Mode.REAL
        );
    }
}
