package br.com.sport.accesscontrol.appconfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigControllerTests {

    @Test
    void loungesEndpointReturnsCentralizedList() {
        var config = new LoungeConfig(List.of("Front 1", "Front 2", "Front 3", "Institucional 1", "Institucional Vereadores"));
        var response = new AppConfigController(config).lounges();

        assertThat(response.get("lounges")).containsExactly(
                "Front 1",
                "Front 2",
                "Institucional 1",
                "Institucional Vereadores",
                "Colaborador"
        );
        assertThat(response.get("lounges")).doesNotContain("Front 3");
        assertThat(config.isValid("Front 3")).isTrue();
        assertThat(config.canonicalName("Front 3")).isEqualTo("Front 2");
        assertThat(config.canonicalName("Instrucional 1")).isEqualTo("Institucional 1");
        assertThat(config.canonicalName("Instrucional Vereadores")).isEqualTo("Institucional Vereadores");
    }

    @Test
    void colaboradorAlwaysIncludedRegardlessOfAppLoungesConfig() {
        // Simulates production: app.lounges = "Front 1,Front 2,..." without "Colaborador"
        var config = new LoungeConfig(List.of("Front 1", "Front 2", "Institucional 1"));
        var response = new AppConfigController(config).lounges();

        assertThat(response.get("lounges")).contains("Colaborador");
        assertThat(config.isValid("Colaborador")).isTrue();
        assertThat(config.isValid("colaborador")).isTrue();
    }
}
