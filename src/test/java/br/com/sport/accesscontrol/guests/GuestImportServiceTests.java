package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuestImportServiceTests {

    @Test
    void importCsvCreatesUpdatesSkipsCompletedAndCollectsRowErrors() throws Exception {
        var guestRepository = mock(GuestRepository.class);
        var stored = new HashMap<String, Guest>();
        var pending = guest("Visitante Antigo", "11144477735", "Front 1");
        var synced = guest("Visitante Sincronizado", "39053344705", "Front 1");
        synced.completeRegistration("81999990000", null, "/uploads/faces/synced.jpg");
        synced.markSynced();
        stored.put(pending.getCpf(), pending);
        stored.put(synced.getCpf(), synced);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            if (guest.getId() == null) {
                ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
            }
            stored.put(guest.getCpf(), guest);
            return guest;
        });
        var service = service(guestRepository);
        var file = csv("""
                NOME,CPF,Telefone,Camarote
                Visitante Novo,52998224725,81999990000,Front 1
                Visitante Atualizado,11144477735,81888880000,Front 2
                Visitante Sincronizado,39053344705,81777770000,Front 2
                CPF Ruim,123,81666660000,Front 1
                Sem Camarote,06331315470,81555550000,
                """);

        var report = service.importFile(file);

        assertThat(report.total()).isEqualTo(5);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.errors()).hasSize(2);
        assertThat(stored.get("52998224725").getStatus()).isEqualTo(GuestStatus.PENDING_REGISTRATION);
        assertThat(stored.get("52998224725").getInvitedLounge()).isEqualTo("Front 1");
        assertThat(stored.get("11144477735").getFullName()).isEqualTo("Visitante Atualizado");
        assertThat(stored.get("11144477735").getInvitedLounge()).isEqualTo("Front 2");
        assertThat(stored.get("39053344705").getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    void previewReportsRequiredMissingHeadersWithoutWriting() throws Exception {
        var service = service(mock(GuestRepository.class));
        var file = csv("""
                Nome,CPF
                Visitante Novo,52998224725
                """);

        var preview = service.preview(file);

        assertThat(preview.totalRowsInFile()).isEqualTo(1);
        assertThat(preview.missingRequiredColumns()).containsExactly("Telefone", "Camarote");
        assertThat(preview.preview()).hasSize(1);
    }

    @Test
    void importAcceptsSemicolonCsv() throws Exception {
        var guestRepository = mock(GuestRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc(anyString())).thenReturn(Optional.empty());
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service = service(guestRepository);
        var file = csv("""
                Nome Completo;CPF;Telefone;Camarote
                Visitante Ponto Virgula;98765432100;81999990000;Front 1
                """);

        var report = service.importFile(file);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void importRejectsMissingRequiredHeaders() {
        var service = service(mock(GuestRepository.class));
        var file = csv("""
                Nome,CPF
                Visitante Novo,52998224725
                """);

        assertThatThrownBy(() -> service.importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Telefone")
                .hasMessageContaining("Camarote");
    }

    @Test
    void importRejectsMoreThanOneThousandRows() {
        var service = service(mock(GuestRepository.class));
        var content = new StringBuilder("Nome Completo,CPF,Telefone,Camarote\n");
        for (int i = 0; i < 1001; i++) {
            content.append("Visitante,52998224725,81999990000,Front 1\n");
        }

        assertThatThrownBy(() -> service.importFile(csv(content.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000 linhas");
    }

    private GuestImportService service(GuestRepository guestRepository) {
        return new GuestImportService(
                guestRepository,
                new LoungeConfig(List.of("Front 1", "Front 2", "Institucional 1", "Institucional Vereadores")),
                mock(AuditService.class)
        );
    }

    private Guest guest(String name, String cpf, String lounge) {
        var now = Instant.parse("2026-06-01T18:00:00Z");
        var guest = new Guest(name, cpf, null, "81999990000", null, "Credenciamento", "Organizador",
                now, now.plusSeconds(3600), LocalDate.of(2026, 6, 1), lounge);
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        return guest;
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile(
                "file",
                "convidados.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
