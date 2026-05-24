package br.com.sport.accesscontrol.appconfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigControllerTests {

    @Test
    void loungesEndpointReturnsCentralizedList() {
        var config = new LoungeConfig(List.of("Camarote 1", "Camarote 2", "Camarote 3", "Camarote 4", "Camarote 5"));
        var response = new AppConfigController(config).lounges();

        assertThat(response.get("lounges")).containsExactly(
                "Camarote 1",
                "Camarote 2",
                "Camarote 3",
                "Camarote 4",
                "Camarote 5"
        );
    }
}
