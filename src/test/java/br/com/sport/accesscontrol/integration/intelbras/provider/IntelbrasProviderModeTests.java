package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    void realProviderDoesNotRunWithoutConfiguredDevices() {
        var connectionService = mock(IntelbrasDeviceConnectionService.class);
        when(connectionService.allConfiguredDevices()).thenReturn(List.of());
        var provider = new IntelbrasRealProvider(
                connectionService,
                mock(IntelbrasCgiClient.class),
                mock(IntelbrasFaceEncoder.class),
                new IntelbrasEventMapper()
        );

        var result = provider.syncPerson(person(true, null));

        assertThat(result.status()).isEqualTo(ProviderSyncStatus.FAILED);
        assertThat(result.message()).contains("No configured Intelbras real devices");
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
