package br.com.sport.accesscontrol.appconfig;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class AppConfigController {

    private final LoungeConfig loungeConfig;

    public AppConfigController(LoungeConfig loungeConfig) {
        this.loungeConfig = loungeConfig;
    }

    @GetMapping("/lounges")
    Map<String, List<String>> lounges() {
        return Map.of("lounges", loungeConfig.getLounges());
    }
}
