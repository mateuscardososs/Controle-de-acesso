package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelbrasProviderModeTests {

    @Test
    void fakeProviderStillReturnsSimulatedResult() {
        var provider = new IntelbrasProvider();

        var result = provider.syncPerson(person(true, "/uploads/faces/fake.png"));

        assertThat(result.status()).isIn(
                ProviderSyncStatus.SUCCESS,
                ProviderSyncStatus.PARTIAL_SUCCESS,
                ProviderSyncStatus.FAILED
        );
        assertThat(result.message()).contains("Simulated");
    }

    @Test
    void realProviderDoesNotRunWithoutOnlineDevicesForAllowedAreas() {
        var connectionService = mock(IntelbrasDeviceConnectionService.class);
        var allowedAreaId = UUID.randomUUID();
        when(connectionService.selectOnlineConfiguredDevicesForAreas(Set.of(allowedAreaId))).thenReturn(List.of());
        var provider = new IntelbrasRealProvider(
                connectionService,
                mock(IntelbrasCgiClient.class),
                mock(IntelbrasFaceEncoder.class),
                new IntelbrasEventMapper(),
                new IntelbrasProperties(),
                new AccessMetricsService(new SimpleMeterRegistry())
        );

        var result = provider.syncPerson(new ProviderPerson(
                PersonType.GUEST,
                UUID.randomUUID(),
                "12345678901",
                null,
                "Visitante",
                null,
                true,
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
                null,
                Set.of(allowedAreaId)
        ));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.message()).contains("Nenhuma controladora online para as áreas permitidas");
        verify(connectionService, never()).selectOnlineConfiguredDevice(any());
    }

    @Test
    void documentIdentityStrategyUsesCpfAsUserIdWithoutCardNo() {
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                UUID.fromString("903956fa-6a1c-4ef8-aaf4-111111111111"),
                "057.316.504-11"
        );

        assertThat(identity.strategy()).isEqualTo(IntelbrasIdentityCodec.Strategy.DOCUMENT);
        assertThat(identity.userId()).isEqualTo("05731650411");
        // CPF is the identifier (userId), NOT the card number — prevent fake card registration
        assertThat(identity.cardNo()).isEmpty();
    }

    @Test
    void documentStrategyNeverUsesDocumentAsCardNo() {
        var cpf = "06331315470";
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                UUID.randomUUID(),
                cpf
        );

        assertThat(identity.userId()).isEqualTo(cpf);
        assertThat(identity.cardNo()).isEmpty();
        assertThat(identity.cardNo()).isNotEqualTo(cpf);
    }

    @Test
    void documentIdentityStrategyFallsBackToShortNumericWithoutDocument() {
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                UUID.fromString("903956fa-6a1c-4ef8-aaf4-111111111111"),
                null
        );

        assertThat(identity.strategy()).isEqualTo(IntelbrasIdentityCodec.Strategy.SHORT_NUMERIC);
        assertThat(identity.userId()).matches("\\d+");
        assertThat(identity.cardNo()).isEqualTo(identity.userId());
    }

    private ProviderPerson person(boolean active, String facePhotoUrl) {
        return new ProviderPerson(
                PersonType.EMPLOYEE,
                UUID.randomUUID(),
                "12345678901",
                "Colaborador",
                facePhotoUrl,
                active,
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600)
        );
    }
}
