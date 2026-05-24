package br.com.sport.accesscontrol.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCleanupSecurityConfigTests {

    @Test
    void cleanupEndpointsAreRestrictedToAdminRole() throws Exception {
        var securityConfig = Files.readString(Path.of("src/main/java/br/com/sport/accesscontrol/config/SecurityConfig.java"));

        assertThat(securityConfig)
                .contains(".requestMatchers(HttpMethod.POST, \"/api/admin/cleanup/**\").hasRole(\"ADMIN\")");
    }
}
