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

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        // CARD_ONLY employee: AccessControlCard is called on all 10 devices
        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

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
        when(cgiClient.isAccessUserPresent(eq("192.168.50.101"), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(cgiClient.isCardAssociatedWithUser(eq("192.168.50.101"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        // CARD_ONLY employee: upsertAccessUser fails on 9 devices
        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

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

        // CARD_ONLY employee: all 3 AccessControlCard calls fail
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
    void cgiErrorDoesNotBecomeSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Error: rejected"));

        // CARD path: AccessControlCard error must not become success
        var result = provider.syncPerson(personWithCard(PersonType.GUEST, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
    }

    @Test
    void faceEncodingFailureWithPhotoUrlBlocksAllTargets() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(2));
        acceptAllCgiCommands();
        when(faceEncoder.toJpegBase64("/uploads/faces/missing.jpg"))
                .thenThrow(new IllegalArgumentException("Face photo file was not found."));

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/uploads/faces/missing.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isEqualTo(2);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.message()).contains("Foto facial não pôde ser processada");
    }

    @Test
    void syncPersonWithNoPhotoUrlSkipsFaceAndSucceeds() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();

        // CARD_ONLY employee: has physical card but no face photo — card sync must succeed
        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.totalTargets()).isEqualTo(1);
    }

    @Test
    void faceSyncErrorDoesNotBecomeSuccessForVisitor() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();
        when(faceEncoder.toJpegBase64("/faces/guest.jpg")).thenReturn("base64");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), eq("base64")))
                .thenThrow(new RuntimeException("Face add rejected"));
        when(cgiClient.isFacePresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        // Visitor without a physical card uses the FACE_ONLY path and fails if face sync fails.
        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/faces/guest.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.totalTargets()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void syncGuestWithoutPhysicalCardUsesFaceOnlyAndClearsTemporaryCardNo() {
        var personId = UUID.fromString("aabbccdd-0011-2233-4455-667788990000");
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photobase64");
        acceptAllCgiCommands();

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, personId,
                "06331315470",              // document = CPF -> UserID
                null,                        // no physical card -> FACE_ONLY
                "Visitante Real",
                "/uploads/faces/guest.jpg",
                true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verify(cgiClient).upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(),
                eq("06331315470"), anyString(), any(), any());
        verify(cgiClient, never()).upsertAccessUser(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("06331315470"), eq("photobase64"));
        verify(cgiClient).clearCardNoForUser(anyString(), anyString(), anyString(),
                eq("06331315470"), anyString(), any(), any());
    }

    @Test
    void syncEmployeeWithoutPhysicalCardUsesFaceOnlyAndClearsTemporaryCardNo() {
        var personId = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/employee.jpg")).thenReturn("empbase64");
        acceptAllCgiCommands();

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE, personId,
                "12345678901",               // document = CPF -> UserID
                null,                         // no physical card -> FACE_ONLY
                "Colaborador Face",
                "/uploads/faces/employee.jpg",
                true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        verify(cgiClient).upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(),
                eq("12345678901"), anyString(), any(), any());
        verify(cgiClient, never()).upsertAccessUser(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("12345678901"), eq("empbase64"));
        verify(cgiClient).clearCardNoForUser(anyString(), anyString(), anyString(),
                eq("12345678901"), anyString(), any(), any());
    }

    @Test
    void visitorWithoutPhysicalCardSucceedsWithFaceOnlyAndFaceWasSent() {
        var personId = UUID.fromString("deadbeef-cafe-babe-feed-facade000001");
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photobase64");
        acceptAllCgiCommands();

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, personId,
                "06331315470", null, "Visitante Real",
                "/uploads/faces/guest.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        verify(cgiClient).upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(),
                eq("06331315470"), anyString(), any(), any());
        verify(cgiClient).replaceFace(anyString(), anyString(), anyString(), eq("06331315470"), eq("photobase64"));
        verify(cgiClient).clearCardNoForUser(anyString(), anyString(), anyString(), eq("06331315470"),
                anyString(), any(), any());
    }

    @Test
    void visitorWithoutPhysicalCardStillSucceedsWhenTemporaryCardNoCleanupIsNotConfirmed() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(faceEncoder.toJpegBase64("/uploads/faces/guest.jpg")).thenReturn("photobase64");
        acceptAllCgiCommands();
        when(cgiClient.clearCardNoForUser(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new IntelbrasCgiClient.CardClearResult(false, "1", "1234567890", "1234567890", true));

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST, UUID.randomUUID(),
                "06331315470", null, "Visitante Real",
                "/uploads/faces/guest.jpg", true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        verify(cgiClient).clearCardNoForUser(anyString(), anyString(), anyString(), eq("06331315470"),
                anyString(), any(), any());
    }

    @Test
    void sendOkButVerifyMissingFailsDeviceAndDoesNotCountAsSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("OK");
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE, UUID.randomUUID(),
                "12345678901",
                "8765432109",
                "Colaborador Teste",
                null,          // no face → CARD_ONLY, face step skipped
                true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.totalTargets()).isEqualTo(1);
        verify(cgiClient, never()).replaceFace(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendOkAndVerifyPresentCountsAsSuccess() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("OK");
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void employeeWithCardSendsPhysicalCardOnly() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();

        // CARD path: physical card must be CardNo; CPF must stay as UserID only
        provider.syncPerson(new ProviderPerson(
                PersonType.EMPLOYEE, UUID.randomUUID(),
                "12345678901",    // document = CPF (UserID)
                "8765432109",     // physical card tag
                "Colaborador com Tag", null, true,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
                null, Set.of(allowedAreaId)
        ));

        var userIdCaptor = ArgumentCaptor.forClass(String.class);
        var cardNoCaptor = ArgumentCaptor.forClass(String.class);
        verify(cgiClient).upsertAccessUser(
                anyString(), anyString(), anyString(),
                userIdCaptor.capture(), cardNoCaptor.capture(), anyString(), any(), any());
        assertThat(userIdCaptor.getValue()).isEqualTo("12345678901");
        assertThat(cardNoCaptor.getValue()).isEqualTo("8765432109");
        assertThat(cardNoCaptor.getValue()).isNotEqualTo(userIdCaptor.getValue());
    }

    @Test
    void cardSyncTimeoutButUserPresentOnControllerCountsAsSynced() {
        // Falso negativo clássico: o upsert lança timeout, mas a controladora já criou o usuário.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("HTTP connect timed out"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.totalTargets()).isEqualTo(1);
    }

    @Test
    void cardSyncTimeoutAndVerifyMissingFailsDevice() {
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("HTTP connect timed out"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.message()).contains("0 de 1");
    }

    @Test
    void faceSyncTimeoutButFaceStoredOnControllerCountsAsSynced() {
        // FACE_ONLY: replaceFace lança, mas usuário e face já estão na controladora.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();
        when(faceEncoder.toJpegBase64("/faces/guest.jpg")).thenReturn("base64");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), eq("base64")))
                .thenThrow(new RuntimeException("HTTP read timed out"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(cgiClient.isFacePresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/faces/guest.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.SUCCESS);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
    }

    @Test
    void faceSyncFailsAndFaceNotPresentStaysFailed() {
        // Falha real: face exigida, usuário até existe, mas a face não está registrada → continua falha.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(1));
        acceptAllCgiCommands();
        when(faceEncoder.toJpegBase64("/faces/guest.jpg")).thenReturn("base64");
        when(cgiClient.replaceFace(anyString(), anyString(), anyString(), anyString(), eq("base64")))
                .thenThrow(new RuntimeException("Face add rejected"));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(cgiClient.isFacePresent(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        var result = provider.syncPerson(person(PersonType.GUEST, Set.of(allowedAreaId), "/faces/guest.jpg"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void oneTimeoutReconciledOneRealFailureReturnsPartial() {
        // Duas controladoras: uma dá timeout mas tem o usuário (reconcilia), a outra falha de verdade.
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(connections(2));
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("HTTP connect timed out"));
        // Só a primeira controladora confirma a presença do usuário.
        when(cgiClient.isAccessUserPresent(eq("192.168.50.101"), anyString(), anyString(), anyString())).thenReturn(true);
        when(cgiClient.isCardAssociatedWithUser(eq("192.168.50.101"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var result = provider.syncPerson(personWithCard(PersonType.EMPLOYEE, Set.of(allowedAreaId), "8765432109"));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.PARTIAL_SUCCESS);
        assertThat(result.totalTargets()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
    }

    private void acceptAllCgiCommands() {
        when(cgiClient.upsertAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("OK");
        when(cgiClient.upsertFaceOnlyAccessUser(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new IntelbrasCgiClient.AccessUserUpsertResult("OK", "insert", "1234567890", true));
        when(cgiClient.clearCardNoForUser(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new IntelbrasCgiClient.CardClearResult(true, "1", "1234567890", "", true));
        when(cgiClient.isAccessUserPresent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(cgiClient.isFacePresent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(cgiClient.isCardAssociatedWithUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
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
