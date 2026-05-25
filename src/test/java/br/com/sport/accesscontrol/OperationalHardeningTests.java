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

    @Test
    void publicVisitorRegistrationIsRateLimitedAndLogsAreSanitizedAtNginx() throws Exception {
        var nginx = Files.readString(Path.of("nginx.conf"));

        assertThat(nginx).contains("limit_req_zone $binary_remote_addr zone=visitor_registration_per_ip:10m rate=10r/m;");
        assertThat(nginx).contains("limit_req zone=visitor_registration_per_ip burst=20 nodelay;");
        assertThat(nginx).contains("limit_req_status 429;");
        assertThat(nginx).contains("Muitas tentativas de cadastro. Aguarde um minuto e tente novamente.");
        assertThat(nginx).contains("location = /api/public/visitor-registration");
        assertThat(nginx).contains("client_max_body_size 10m;");
        assertThat(nginx).contains("log_format main_sanitized");
        assertThat(nginx).contains("\"$request_method $uri $server_protocol\"");
        assertThat(nginx).doesNotContain("$request_body");
        assertThat(nginx).doesNotContain("$request_uri");
    }

    @Test
    void productionExampleAllowsPublicRegistrationDomainInCorsAndBaseUrls() throws Exception {
        var envExample = Files.readString(Path.of(".env.production.example"));

        assertThat(envExample).contains("PUBLIC_BASE_URL=https://cadastro.seudominio.com.br");
        assertThat(envExample).contains("FRONTEND_PUBLIC_BASE_URL=https://cadastro.seudominio.com.br");
        assertThat(envExample).contains(
                "APP_CORS_ALLOWED_ORIGINS=https://cadastro.seudominio.com.br,http://admin.local,http://localhost,http://127.0.0.1"
        );
    }
}
