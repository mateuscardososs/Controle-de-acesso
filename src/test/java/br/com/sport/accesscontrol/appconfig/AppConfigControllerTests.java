package br.com.sport.accesscontrol.appconfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigControllerTests {

    @Test
    void loungesEndpointReturnsCentralizedList() {
        var config = new LoungeConfig(List.of("Front 1", "Front 2", "Front 3", "Institucional 1", "Institucional Vereador"));
        var response = new AppConfigController(config).lounges();

        assertThat(response.get("lounges")).containsExactly(
                "Front 1",
                "Front 2",
                "Front 3",
                "Institucional 1",
                "Institucional Vereador"
        );
    }
}
