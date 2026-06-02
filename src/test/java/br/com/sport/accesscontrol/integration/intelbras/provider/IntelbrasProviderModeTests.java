package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpc2Client;
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
                mock(IntelbrasRpc2Client.class),
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
        assertThat(result.message()).contains("Nenhuma catraca encontrada para o camarote selecionado");
        verify(connectionService, never()).selectOnlineConfiguredDevice(any());
    }

    @Test
    void documentIdentityStrategyUsesCpfAsUserIdAndUuidDerivedCardNo() {
        var personId = UUID.fromString("903956fa-6a1c-4ef8-aaf4-111111111111");
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                personId,
                "057.316.504-11"
        );

        assertThat(identity.strategy()).isEqualTo(IntelbrasIdentityCodec.Strategy.DOCUMENT);
        assertThat(identity.userId()).isEqualTo("05731650411");
        // CardNo is now shortNumeric(personId) — unique per UUID, no collision between CPFs
        assertThat(identity.cardNo()).isEqualTo(IntelbrasIdentityCodec.shortNumeric(personId));
        assertThat(identity.cardNo()).matches("\\d{6}");  // shortNumeric always produces 6 digits
        assertThat(identity.cardNo()).isNotEqualTo("0573165041"); // NOT the old CPF[0:10]
        assertThat(identity.cardNo()).isNotEqualTo("05731650411"); // NOT the full CPF
    }

    @Test
    void documentStrategyCardNoIsUuidDerivedNotCpfPrefix() {
        var personId = UUID.fromString("12345678-abcd-ef01-2345-6789abcdef01");
        var cpf = "06331315470";
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                personId,
                cpf
        );

        assertThat(identity.userId()).isEqualTo(cpf);
        // CardNo must be UUID-derived (6 digits), never the CPF or its prefix
        assertThat(identity.cardNo()).isEqualTo(IntelbrasIdentityCodec.shortNumeric(personId));
        assertThat(identity.cardNo()).matches("\\d{6}");
        assertThat(identity.cardNo()).isNotEqualTo("0633131547"); // NOT CPF[0:10]
        assertThat(identity.cardNo()).isNotEqualTo(cpf);          // NOT full CPF
        // Two different CPFs with same prefix produce different CardNos (no collision)
        var otherPersonId = UUID.fromString("99999999-abcd-ef01-2345-6789abcdef99");
        var collisionCandidate = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                otherPersonId,
                "06331315401"  // same first 10 digits as cpf above
        );
        assertThat(identity.cardNo()).isNotEqualTo(collisionCandidate.cardNo());
    }

    @Test
    void documentIdentityStrategyWithoutDocumentDoesNotGenerateCardNo() {
        var identity = IntelbrasIdentityCodec.resolve(
                IntelbrasIdentityCodec.Strategy.DOCUMENT,
                PersonType.GUEST,
                UUID.fromString("903956fa-6a1c-4ef8-aaf4-111111111111"),
                null
        );

        assertThat(identity.strategy()).isEqualTo(IntelbrasIdentityCodec.Strategy.DOCUMENT);
        assertThat(identity.userId()).isEmpty();
        assertThat(identity.cardNo()).isEmpty();
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
