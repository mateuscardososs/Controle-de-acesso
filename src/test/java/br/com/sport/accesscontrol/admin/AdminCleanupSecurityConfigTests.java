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

    @Test
    void accessEventCsvExportUsesAuthenticatedLogViewerPolicy() throws Exception {
        var securityConfig = Files.readString(Path.of("src/main/java/br/com/sport/accesscontrol/config/SecurityConfig.java"));

        assertThat(securityConfig)
                .contains(".requestMatchers(HttpMethod.GET, \"/api/access-events/**\").hasAnyRole(\"ADMIN\", \"HR\", \"SECURITY_VIEWER\")");
    }

    @Test
    void employeeCleanupPageRefreshesTableAfterSuccessfulCleanup() throws Exception {
        var page = Files.readString(Path.of("frontend/app/employees/page.tsx"));

        assertThat(page).contains("queryClient.setQueryData<Employee[]>([\"employees\"], [])");
        assertThat(page).contains("await queryClient.invalidateQueries({ queryKey: [\"employees\"] })");
        assertThat(page).contains("cleanupEmployees.isPending");
    }
}
