package br.com.sport.accesscontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlApiApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("access_control")
            .withUsername("access_user")
            .withPassword("access_pass");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void contextLoads() {
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void healthEndpointsReturnOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void loginWithValidCredentialsReturnsJwt() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@empresa.local",
                                  "password": "Admin@123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void loginWithInvalidCredentialsReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@empresa.local",
                                  "password": "wrong"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAccessesProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/employees")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void hrAccessesEmployees() throws Exception {
        var token = tokenForRegisteredUser("hr-" + UUID.randomUUID() + "@empresa.local", "HR");

        mockMvc.perform(get("/api/employees")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void securityViewerDoesNotCreateEmployee() throws Exception {
        var token = tokenForRegisteredUser("viewer-" + UUID.randomUUID() + "@empresa.local", "SECURITY_VIEWER");

        mockMvc.perform(post("/api/employees")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeePayload()))
                .andExpect(status().isForbidden());
    }

    @Test
    void securityViewerAccessesDashboardSummary() throws Exception {
        var token = tokenForRegisteredUser("viewer-" + UUID.randomUUID() + "@empresa.local", "SECURITY_VIEWER");

        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEmployees").isNumber())
                .andExpect(jsonPath("$.totalDevices").isNumber())
                .andExpect(jsonPath("$.todayEvents").isNumber())
                .andExpect(jsonPath("$.deniedAccesses").isNumber());
    }


    @Test
    void createsEmployee() throws Exception {
        createEmployee();
    }

    @Test
    void createsArea() throws Exception {
        createArea();
    }

    @Test
    void createsDevice() throws Exception {
        var areaId = createArea();
        createDevice(areaId);
    }

    @Test
    void simulatesAccessEvent() throws Exception {
        var employeeId = createEmployee();
        var areaId = createArea();
        var deviceId = createDevice(areaId);

        var payload = """
                {
                  "personType": "EMPLOYEE",
                  "personId": "%s",
                  "deviceId": "%s",
                  "eventType": "ENTRY",
                  "accessResult": "ALLOWED",
                  "origin": "SIMULATION",
                  "rawPayload": {
                    "source": "test"
                  }
                }
                """.formatted(employeeId, deviceId);

        mockMvc.perform(post("/api/access-events/simulate")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personType").value("EMPLOYEE"))
                .andExpect(jsonPath("$.personId").value(employeeId.toString()))
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.areaId").value(areaId.toString()))
                .andExpect(jsonPath("$.accessResult").value("ALLOWED"));
    }

    private UUID createEmployee() throws Exception {
        var result = mockMvc.perform(post("/api/employees")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeePayload()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Colaborador Exemplo"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        return idFrom(result.getResponse().getContentAsString());
    }

    private String employeePayload() {
        var cpf = "000" + Math.abs(UUID.randomUUID().hashCode());
        return """
                {
                  "fullName": "Colaborador Exemplo",
                  "cpf": "%s",
                  "email": "colaborador.%s@empresa.local",
                  "phone": "81999990000",
                  "registrationNumber": "EMP-%s",
                  "status": "ACTIVE"
                }
                """.formatted(cpf, cpf, cpf);
    }

    private UUID createArea() throws Exception {
        var payload = """
                {
                  "name": "Ilha do Retiro - Social",
                  "description": "Acesso social"
                }
                """;

        var result = mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ilha do Retiro - Social"))
                .andReturn();

        return idFrom(result.getResponse().getContentAsString());
    }

    private UUID createDevice(UUID areaId) throws Exception {
        var payload = """
                {
                  "name": "Catraca Social 01",
                  "model": "Intelbras SS 5530 MF FACE",
                  "serialNumber": "INTELBRAS-%s",
                  "ipAddress": "192.168.10.50",
                  "location": "Entrada social",
                  "operationType": "ENTRY_EXIT",
                  "status": "UNKNOWN",
                  "areaId": "%s"
                }
                """.formatted(UUID.randomUUID(), areaId);

        var result = mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Catraca Social 01"))
                .andExpect(jsonPath("$.areaId").value(areaId.toString()))
                .andReturn();

        return idFrom(result.getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return UUID.fromString(node.get("id").asText());
    }

    private String adminToken() throws Exception {
        return login("admin@empresa.local", "Admin@123456");
    }

    private String tokenForRegisteredUser(String email, String role) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Test User",
                                  "email": "%s",
                                  "password": "Admin@123456",
                                  "role": "%s"
                                }
                                """.formatted(email, role)))
                .andExpect(status().isCreated());

        return login(email, "Admin@123456");
    }

    private String login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
