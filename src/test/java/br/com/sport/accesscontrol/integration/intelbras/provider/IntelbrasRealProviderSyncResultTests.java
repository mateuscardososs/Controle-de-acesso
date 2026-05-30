package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelbrasRealProviderSyncResultTests {

    private IntelbrasDeviceConnectionService connectionService;
    private IntelbrasCgiClient cgiClient;
    private IntelbrasFaceEncoder faceEncoder;
    private IntelbrasRealProvider provider;

    @BeforeEach
    void setUp() {
        connectionService = mock(IntelbrasDeviceConnectionService.class);
        cgiClient = mock(IntelbrasCgiClient.class);
        faceEncoder = mock(IntelbrasFaceEncoder.class);
        provider = new IntelbrasRealProvider(
                connectionService,
                cgiClient,
                faceEncoder,
                new IntelbrasEventMapper(),
                new IntelbrasProperties(),
                new AccessMetricsService(new SimpleMeterRegistry())
        );
    }

    @Test
    void providerAttemptsAllSelectedControllers() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(10));
        acceptAllCgiCommands();

        var result = provider.syncPerson(person(PersonType.EMPLOYEE, Set.of(allowedAreaId), null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(10);
        assertThat(result.successCount()).isEqualTo(10);
        verify(cgiClient, times(10)).upsertAccessUser(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void oneOfTenSuccessesReturnsPartialInsteadOfFullSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(10));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    var host = invocation.getArgument(0, String.class);
                    if ("192.168.50.101".equals(host)) {
                        return "OK";
                    }
                    throw new RuntimeException("CGI error");
                });
        when(cgiClient.findAccessControlCards(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.of("UserID", invocation.getArgument(3, String.class))));

        var result = provider.syncPerson(person(PersonType.EMPLOYEE, Set.of(allowedAreaId), null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.PARTIAL_SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(10);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(9);
    }

    @Test
    void zeroSuccessesReturnsFailed() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(3));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("CGI error"));

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isEqualTo(3);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(3);
        assertThat(result.message()).contains("0 de 3");
    }

    @Test
    void selectedDevicesEmptyReturnsFailedWithZeroTargets() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(List.of());

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isZero();
        assertThat(result.successCount()).isZero();
    }

    @Test
    void cgiErrorDoesNotBecomeSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Error: rejected"));

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
    }

    @Test
    void faceSyncErrorDoesNotBecomeSuccessForVisitor() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();
        when(faceEncoder.toJpegBase64("/faces/guest.jpg")).thenReturn("base64");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), anyString(), eq("base64")))
                .thenThrow(new RuntimeException("Face add rejected"));

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/faces/guest.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    private void acceptAllCgiCommands() {
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("OK");
        when(cgiClient.findAccessControlCards(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.of("UserID", invocation.getArgument(3, String.class))));
    }

    private ProviderPerson person(PersonType type, Set<UUID> allowedAreaIds, String facePhotoUrl) {
        return new ProviderPerson(
                type,
                UUID.randomUUID(),
                "12345678901",
                null,
                type == PersonType.GUEST ? "Visitante Teste" : "Colaborador Teste",
                facePhotoUrl,
                true,
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
                null,
                allowedAreaIds
        );
    }

    private List<IntelbrasDeviceConnection> connections(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> {
                    var ip = index == 1 ? "192.168.50.101" : "192.168.50." + (100 + index);
                    var area = new Area("Area " + index, "Area " + index, true);
                    ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
                    var device = new Device("Catraca " + index, "Intelbras SS 5531 MF W", "SN" + index, ip,
                            "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
                    ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
                    device.setIntelbrasUsername("admin");
                    device.setIntelbrasPassword("secret");
                    return new IntelbrasDeviceConnection(device, ip, "admin", "secret");
                })
                .toList();
    }
}
