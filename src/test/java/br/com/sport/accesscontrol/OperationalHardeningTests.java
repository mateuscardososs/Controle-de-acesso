package br.com.sport.accesscontrol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalHardeningTests {

    @Test
    void prometheusDoesNotScrapeMissingNodeExporter() throws Exception {
        var prometheus = Files.readString(Path.of("infra/prometheus/prometheus.yml"));

        assertThat(prometheus).doesNotContain("node-exporter");
    }

    @Test
    void productionComposeDoesNotDefaultIntelbrasPasswordOrLeakBackendEnvToGrafana() throws Exception {
        var compose = Files.readString(Path.of("docker-compose.prod.yml"));

        assertThat(compose).doesNotContain("APP_INTELBRAS_DEFAULT_PASSWORD:-admin123");
        assertThat(compose).contains("APP_INTELBRAS_DEFAULT_PASSWORD: ${APP_INTELBRAS_DEFAULT_PASSWORD:-}");
        assertThat(compose).doesNotContain("""
          grafana:
            image: grafana/grafana:11.3.0
            restart: unless-stopped
            env_file:
        """);
    }
}
