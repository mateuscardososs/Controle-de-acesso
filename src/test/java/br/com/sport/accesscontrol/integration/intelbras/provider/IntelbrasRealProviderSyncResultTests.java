package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpc2Client;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The real sync path uses CGI exclusively ({@code recordUpdater.cgi} + {@code FaceInfoManager.cgi}).
 * RPC2 must never be touched during sync (it is closed/empty on this firmware).
 */
class IntelbrasRealProviderSyncResultTests {

    private IntelbrasDeviceConnectionService connectionService;
    private IntelbrasCgiClient cgiClient;
    private IntelbrasRpc2Client rpc2Client;
    private IntelbrasFaceEncoder faceEncoder;
    private IntelbrasRealProvider provider;

    @BeforeEach
    void setUp() {
        connectionService = mock(IntelbrasDeviceConnectionService.class);
        cgiClient = mock(IntelbrasCgiClient.class);
        rpc2Client = mock(IntelbrasRpc2Client.class);
        faceEncoder = mock(IntelbrasFaceEncoder.class);
        provider = new IntelbrasRealProvider(
                connectionService,
                cgiClient,
                rpc2Client,
                faceEncoder,
                new IntelbrasEventMapper(),
                new IntelbrasProperties(),
                new AccessMetricsService(new SimpleMeterRegistry())
        );
        acceptAllCgiCommands();
    }

    @Test
    void syncUsesCgiAndNeverCallsRpc2() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(3));

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verify(cgiClient, times(3)).upsertAccessUser(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void rpc2EmptyReplyDoesNotBlockCgiSync() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));

        // O sync CGI é totalmente independente de /RPC2 (que está quebrado/empty no firmware):
        // o provider nunca toca no rpc2Client, então um /RPC2 com "Empty reply" não impede o sync.
        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void faceOnlyVisitorUsesFullUpsertWithCardAndNeverClears() {
        // Visitante sem cartão físico: fluxo COMPLETO (recordUpdater com CardNo = CPF) + face,
        // SEM face_only_minimal e SEM clear — é o que faz aparecer em "Gestão de usuários".
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photo64");
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(cgiClient.isAccessRecordPresentByRecNo(anyString(), anyString(), anyString(), eq("49"))).thenReturn(false);

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, UUID.randomUUID(), "06331315470", null, "Visitante",
                "/uploads/faces/guest.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, Set.of(allowedAreaId)));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        // CardNo = CPF (documento) no fluxo completo.
        verify(cgiClient).upsertAccessUser(anyString(), anyString(), anyString(), eq("06331315470"),
                eq("06331315470"), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("06331315470"), eq("photo64"));
        verify(cgiClient).isAccessUserPresent(anyString(), anyString(), anyString(), eq("06331315470"));
        verify(cgiClient).isCardAssociatedWithUser(anyString(), anyString(), anyString(), eq("06331315470"), eq("06331315470"));
        verify(cgiClient).isAccessRecordPresentByRecNo(anyString(), anyString(), anyString(), eq("49"));
        verify(cgiClient, never()).upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        // NÃO pode limpar o cartão temporário depois da face.
        verify(cgiClient, never()).clearCardNoForUser(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void acceptedRecordUpdaterRecNoAndFaceAddConfirmsEvenWhenBestEffortVerificationUnavailable() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photo64");
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn("RecNo=49");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), eq("photo64")))
                .thenReturn("OK");
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("recordFinder unavailable"));
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("card lookup unavailable"));
        when(cgiClient.isAccessRecordPresentByRecNo(anyString(), anyString(), anyString(), eq("49")))
                .thenThrow(new RuntimeException("recno lookup unavailable"));

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, UUID.randomUUID(), "06331315470", null, "Visitante",
                "/uploads/faces/guest.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, Set.of(allowedAreaId)));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        verify(cgiClient).upsertAccessUser(anyString(), anyString(), anyString(), eq("06331315470"),
                eq("06331315470"), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("06331315470"), eq("photo64"));
        verify(cgiClient, never()).isFacePresent(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void recordUpdaterRecNoPlusFaceOkIsSuccessEvenWhenUserIdAndCardLookupsEmpty() {
        // getInfo da face é 501 (ignorado). Se recordUpdater devolveu RecNo e a face foi aceita,
        // a confirmação por RecNo basta — não derruba o sync.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("recno=49");
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(cgiClient.isAccessRecordPresentByRecNo(anyString(), anyString(), anyString(), eq("49"))).thenReturn(true);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
    }

    @Test
    void faceGetInfo501NeverBlocksSync() {
        // Mesmo que getInfo (isFacePresent) lance 501, a verificação NÃO o usa -> sync continua OK.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photo64");
        when(cgiClient.isFacePresent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("501 Not Implemented"));

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, UUID.randomUUID(), "06331315470", null, "Visitante",
                "/uploads/faces/guest.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, Set.of(allowedAreaId)));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verify(cgiClient, never()).isFacePresent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cardAndFaceCallsRecordUpdaterAndFaceInfoManager() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/emp.jpg")).thenReturn("emp64");

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE, UUID.randomUUID(), "12345678901", "8765432109", "Colaborador",
                "/uploads/faces/emp.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, Set.of(allowedAreaId)));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verify(cgiClient).upsertAccessUser(anyString(), anyString(), anyString(), eq("12345678901"),
                eq("8765432109"), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("12345678901"), eq("emp64"));
        verify(cgiClient, never()).upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void providerSyncsAllSelectedControllers() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(10));

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(10);
        assertThat(result.successCount()).isEqualTo(10);
    }

    @Test
    void oneOfTenConfirmedReturnsPartialInsteadOfFullSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(10));
        // recordUpdater falha em 9; só .101 confirma a presença.
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    if ("192.168.50.101".equals(invocation.getArgument(0, String.class))) {
                        return "OK";
                    }
                    throw new RuntimeException("CGI error");
                });
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> "192.168.50.101".equals(invocation.getArgument(0, String.class)));

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.PARTIAL_SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(10);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(9);
    }

    @Test
    void zeroConfirmedReturnsFailed() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(3));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("CGI error"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        var result = provider.syncPerson(personWithCard(PersonType.GUEST, Set.of(allowedAreaId), "8765432109"));

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

        var result = provider.syncPerson(personWithCard(PersonType.GUEST, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isZero();
        assertThat(result.successCount()).isZero();
    }

    @Test
    void faceEncodingFailureWithPhotoUrlBlocksAllTargets() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(2));
        when(faceEncoder.toJpegBase64("/uploads/faces/missing.jpg"))
                .thenThrow(new IllegalArgumentException("Face photo file was not found."));

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/uploads/faces/missing.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.message()).contains("Foto facial não pôde ser processada");
        verifyNoInteractions(rpc2Client);
    }

    @Test
    void sendOkButVerifyMissingFailsDeviceAndDoesNotCountAsSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        // recordUpdater aceita (200/OK), mas a verificação não encontra o usuário.
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void sendOkAndVerifyPresentCountsAsSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void cardSendTimeoutButUserPresentCountsAsSynced() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("HTTP connect timed out"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
    }

    @Test
    void faceSendFailureStaysFailed() {
        // replaceFace lançou (rejeição/erro real) — sem getInfo (501) não há como confirmar a face,
        // então o device NÃO conta como sucesso (a retentativa reenvia depois).
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/faces/guest.jpg")).thenReturn("base64");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), eq("base64")))
                .thenThrow(new RuntimeException("FaceInfoManager rejected"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/faces/guest.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void verifyUnavailableOnAllControllersIsFailedNotWarnings() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(5));
        // Verificação não pode rodar (ex.: HTML/login na recordFinder) em todas -> 0 confirmadas -> FAILED, nunca warnings.
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("returned HTML/login page"));

        var result = provider.syncPerson(personWithCard(PersonType.GUEST, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.totalTargets()).isEqualTo(5);
        assertThat(result.message()).contains("0 de 5");
    }

    @Test
    void employeeWithCardSendsPhysicalCardAsCardNoKeepingCpfAsUserId() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));

        provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE, UUID.randomUUID(), "12345678901", "8765432109", "Colaborador com Tag",
                null, true, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, Set.of(allowedAreaId)));

        var userIdCaptor = ArgumentCaptor.forClass(String.class);
        var cardNoCaptor = ArgumentCaptor.forClass(String.class);
        verify(cgiClient).upsertAccessUser(anyString(), anyString(), anyString(),
                userIdCaptor.capture(), cardNoCaptor.capture(), anyString(), any(), any());
        assertThat(userIdCaptor.getValue()).isEqualTo("12345678901");
        assertThat(cardNoCaptor.getValue()).isEqualTo("8765432109");
    }

    private void acceptAllCgiCommands() {
        // Fluxo único: recordUpdater (com RecNo) + FaceInfoManager.add. Sem upsertFaceOnly e sem clear.
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("recno=49 OK");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("OK");
        // Verificação primária por UserID confirma o happy-path; os fallbacks (CardNo/RecNo) ficam
        // em default false e são stubados só nos testes que os exercitam — assim os testes de "ausente"
        // realmente falham quando isAccessUserPresent=false.
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private ProviderPerson person(PersonType type, Set<UUID> allowedAreaIds, String facePhotoUrl) {
        return new ProviderPerson(
                type, UUID.randomUUID(), "12345678901", null,
                type == PersonType.GUEST ? "Visitante Teste" : "Colaborador Teste",
                facePhotoUrl, true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, allowedAreaIds
        );
    }

    private ProviderPerson personWithCard(PersonType type, Set<UUID> allowedAreaIds, String physicalCardNo) {
        return new ProviderPerson(
                type, UUID.randomUUID(), "12345678901", physicalCardNo,
                type == PersonType.GUEST ? "Visitante Teste" : "Colaborador Teste",
                null, true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, allowedAreaIds
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
