package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.areas.AreaRepository;
import br.com.sport.accesscontrol.areas.LoungeAreaResolver;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.integration.sync.GuestReadyForSyncEvent;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GuestAdminVisitorRegistrationTests {

    @Test
    void adminFront1RegistrationGeneratesPortariaAndFront1() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2");

        var response = service.adminVisitorRegistration(
                "Visitante Front 1",
                "52998224725",
                "front1@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        );

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Front 1");
    }

    @Test
    void adminVisitorRegistrationWithPhotoPublishesAutomaticSyncEvent() {
        var guestRepository = mock(GuestRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = serviceWithAreas(guestRepository, publisher, "Portaria", "Front 1");

        var response = service.adminVisitorRegistration(
                "Visitante Sync Auto",
                "52998224725",
                "sync.auto@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        );

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        verify(publisher).publishEvent(new GuestReadyForSyncEvent(response.id()));
    }

    @Test
    void adminRegistrationMapsOfficialLoungesToPortariaAndSelectedLounge() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2", "Institucional 1", "Institucional Vereadores");

        // Lounges sem catracas compartilhadas: apenas Portaria + área própria
        for (var lounge : List.of("Front 1", "Front 2")) {
            var response = service.adminVisitorRegistration(
                    "Visitante " + lounge,
                    "11144477735",
                    "visitante@empresa.local",
                    "81999990000",
                    LocalDate.of(2026, 6, 10),
                    lounge,
                    photo()
            );

            assertThat(response.allowedAreaNames()).containsExactly("Portaria", lounge);
        }

        // Institucional 1 e Institucional Vereadores compartilham catracas físicas:
        // ambos recebem Portaria + as DUAS áreas institucionais.
        for (var lounge : List.of("Institucional 1", "Institucional Vereadores")) {
            var response = service.adminVisitorRegistration(
                    "Visitante " + lounge,
                    "11144477735",
                    "visitante@empresa.local",
                    "81999990000",
                    LocalDate.of(2026, 6, 10),
                    lounge,
                    photo()
            );

            assertThat(response.allowedAreaNames())
                    .containsExactlyInAnyOrder("Portaria", "Institucional 1", "Institucional Vereadores");
        }
    }

    @Test
    void adminFront2RegistrationDoesNotGenerateFront1() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2", "Institucional 1", "Institucional Vereadores");

        var response = service.adminVisitorRegistration(
                "Visitante Front 2",
                "11144477735",
                "front2@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 2",
                photo()
        );

        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Front 2");
        assertThat(response.allowedAreaNames()).doesNotContain("Front 1");
    }

    @Test
    void adminFront3AliasStoresFront2AndResolvesFront2Area() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2");

        var response = service.adminVisitorRegistration(
                "Visitante Front 3 Visual",
                "52998224725",
                "front3@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 3",
                photo()
        );

        assertThat(response.invitedLounge()).isEqualTo("Front 2");
        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Front 2");
    }

    @Test
    void adminInstitucional1ResolvesInstrucionalAreaAlias() {
        var service = serviceWithAreas("Portaria", "Instrucional 1");

        var response = service.adminVisitorRegistration(
                "Visitante Institucional",
                "52998224725",
                "institucional@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Institucional 1",
                photo()
        );

        assertThat(response.invitedLounge()).isEqualTo("Institucional 1");
        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Instrucional 1");
    }

    @Test
    void adminInstitucionalVereadoresResolvesInstrucionalAreaAlias() {
        var service = serviceWithAreas("Portaria", "Instrucional Vereadores");

        var response = service.adminVisitorRegistration(
                "Visitante Vereadores",
                "52998224725",
                "vereadores@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Institucional Vereadores",
                photo()
        );

        assertThat(response.invitedLounge()).isEqualTo("Institucional Vereadores");
        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Instrucional Vereadores");
    }

    @Test
    void adminRegistrationRejectsInvalidCpf() {
        var service = serviceWithAreas("Portaria", "Front 1");

        assertThatThrownBy(() -> service.adminVisitorRegistration(
                "Visitante CPF",
                "11111111111",
                "cpf@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CPF inválido");
    }

    @Test
    void adminRegistrationAcceptsOptionalEmail() {
        var service = serviceWithAreas("Portaria", "Front 1");

        var response = service.adminVisitorRegistration(
                "Visitante Sem Email",
                "52998224725",
                "",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        );

        assertThat(response.email()).isNull();
        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
    }

    @Test
    void adminRegistrationCalculatesValidityFromInvitedDay() {
        var service = serviceWithAreas("Portaria", "Front 1");
        var invitedDay = LocalDate.of(2026, 6, 10);
        var zone = ZoneId.of("America/Recife");

        var response = service.adminVisitorRegistration(
                "Visitante Validade",
                "52998224725",
                "validade@empresa.local",
                "81999990000",
                invitedDay,
                "Front 1",
                photo()
        );

        assertThat(response.visitStart()).isEqualTo(invitedDay.atTime(15, 0).atZone(zone).toInstant());
        assertThat(response.visitEnd()).isEqualTo(invitedDay.plusDays(1).atTime(4, 0).atZone(zone).toInstant());
        assertThat(response.invitedDay()).isEqualTo(invitedDay);
        assertThat(response.invitedLounge()).isEqualTo("Front 1");
    }

    @Test
    void colaboradorRegistrationGetsAllActiveAreas() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2", "Institucional 1");

        var response = service.adminVisitorRegistration(
                "Colaborador Teste",
                "52998224725",
                "colaborador@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Colaborador",
                photo()
        );

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(response.invitedLounge()).isEqualTo("Colaborador");
        // Collaborator gets ALL active areas — Portaria, Front 1, Front 2, Institucional 1
        assertThat(response.allowedAreaNames()).containsExactlyInAnyOrder(
                "Portaria", "Front 1", "Front 2", "Institucional 1");
    }

    @Test
    void colaboradorRegistrationIsAcceptedByPublicFlow() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2");

        // Collaborator lounge is always valid regardless of app.lounges list
        var response = service.adminVisitorRegistration(
                "Colaborador Operacional",
                "52998224725",
                null,
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Colaborador",
                photo()
        );

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.allowedAreaNames()).containsExactlyInAnyOrder("Portaria", "Front 1", "Front 2");
    }

    @Test
    void regularVisitorRegistrationUnaffectedByColaboradorAddition() {
        // Verify that existing visitor lounge sync is not broken
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2");

        var response = service.adminVisitorRegistration(
                "Visitante Normal",
                "52998224725",
                null,
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        );

        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Front 1");
        assertThat(response.allowedAreaNames()).doesNotContain("Front 2");
    }

    @Test
    void adminRegistrationRejectsInvalidLounge() {
        var service = serviceWithAreas("Portaria", "Front 1", "Front 2", "Institucional 1", "Institucional Vereadores");

        assertThatThrownBy(() -> service.adminVisitorRegistration(
                "Visitante Camarote",
                "52998224725",
                "camarote@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Área VIP",
                photo()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Camarote inválido");
    }

    @Test
    void adminRegistrationRejectsWhenOfficialLoungeAreaIsMissing() {
        var service = serviceWithAreas("Portaria", "Front 2", "Institucional 1", "Institucional Vereadores");

        assertThatThrownBy(() -> service.adminVisitorRegistration(
                "Visitante Front 1",
                "52998224725",
                "front1@empresa.local",
                "81999990000",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                photo()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Área ativa do camarote não configurada: Front 1");
    }

    @Test
    void requestSyncRecalculatesIncompleteAllowedAreasBeforeQueueing() {
        var guestRepository = mock(GuestRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = serviceWithAreas(guestRepository, publisher, "Portaria", "Front 1");
        var guest = new Guest(
                "Visitante Front 1",
                "52998224725",
                null,
                "81999990000",
                null,
                "Evento",
                "Host",
                Instant.parse("2026-06-10T18:00:00Z"),
                Instant.parse("2026-06-11T07:00:00Z"),
                LocalDate.of(2026, 6, 10),
                "Front 1"
        );
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        guest.completeRegistration("81999990000", null, "/uploads/faces/admin.png");
        guest.replaceAllowedAreas(java.util.Set.of(area("Portaria")));
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.requestSync(guest.getId());

        assertThat(response.allowedAreaNames()).containsExactly("Portaria", "Front 1");
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        verify(publisher).publishEvent(any(GuestReadyForSyncEvent.class));
    }

    @Test
    void cpfCheckinWithFaceStoresPhotoAndPublishesAutomaticSyncEvent() {
        var guestRepository = mock(GuestRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = serviceWithAreas(guestRepository, publisher, "Portaria", "Front 1");
        var guest = new Guest(
                "Visitante CPF",
                "52998224725",
                null,
                "81999990000",
                null,
                "Evento",
                "Host",
                Instant.parse("2026-06-10T18:00:00Z"),
                Instant.parse("2026-06-11T07:00:00Z"),
                LocalDate.of(2026, 6, 10),
                "Front 1"
        );
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        when(guestRepository.findFirstByCpfAndStatusOrderByVisitStartDesc("52998224725", GuestStatus.PENDING_REGISTRATION))
                .thenReturn(Optional.of(guest));

        var response = service.completeCheckinByCpf("529.982.247-25", "data:image/jpeg;base64,Zm90bw==");

        assertThat(response.success()).isTrue();
        assertThat(guest.getStatus()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(guest.getFacePhotoUrl()).isEqualTo("/uploads/faces/admin.png");
        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        verify(publisher).publishEvent(new GuestReadyForSyncEvent(guest.getId()));
    }

    @Test
    void cpfCheckinWithoutFaceDoesNotPublishSyncEvent() {
        var guestRepository = mock(GuestRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = serviceWithAreas(guestRepository, publisher, "Portaria", "Front 1");
        var guest = new Guest(
                "Visitante Sem Foto",
                "52998224725",
                null,
                "81999990000",
                null,
                "Evento",
                "Host",
                Instant.parse("2026-06-10T18:00:00Z"),
                Instant.parse("2026-06-11T07:00:00Z"),
                LocalDate.of(2026, 6, 10),
                "Front 1"
        );
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        when(guestRepository.findFirstByCpfAndStatusOrderByVisitStartDesc("52998224725", GuestStatus.PENDING_REGISTRATION))
                .thenReturn(Optional.of(guest));

        var response = service.completeCheckinByCpf("529.982.247-25", null);

        assertThat(response.success()).isTrue();
        assertThat(guest.getStatus()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(guest.getFacePhotoUrl()).isNull();
        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.NOT_REQUIRED);
        verify(publisher, never()).publishEvent(any(GuestReadyForSyncEvent.class));
    }

    private GuestService serviceWithAreas(String... areaNames) {
        var guestRepository = mock(GuestRepository.class);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
            return guest;
        });
        return serviceWithAreas(guestRepository, mock(ApplicationEventPublisher.class), areaNames);
    }

    private GuestService serviceWithAreas(GuestRepository guestRepository, ApplicationEventPublisher publisher,
                                          String... areaNames) {
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            if (guest.getId() == null) {
                ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
            }
            return guest;
        });

        var faceStorage = mock(FaceStorageService.class);
        when(faceStorage.store(any(), any())).thenReturn("/uploads/faces/admin.png");
        when(faceStorage.storeBase64(anyString(), any())).thenReturn("/uploads/faces/admin.png");

        var areaRepository = mock(AreaRepository.class);
        var areas = new java.util.LinkedHashMap<String, Area>();
        for (var areaName : areaNames) {
            areas.put(areaName.toLowerCase(java.util.Locale.ROOT), area(areaName));
        }
        when(areaRepository.findByNameIgnoreCase(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return Optional.ofNullable(areas.get(name.toLowerCase(java.util.Locale.ROOT)));
        });
        // Required for Colaborador path which calls resolveAllForEmployee() → findAllByActiveTrue()
        when(areaRepository.findAllByActiveTrue()).thenReturn(new java.util.ArrayList<>(areas.values()));

        return new GuestService(
                guestRepository,
                mock(GuestInviteRepository.class),
                faceStorage,
                mock(AuditService.class),
                mock(RealtimePublisherService.class),
                mock(MailService.class),
                publisher,
                new LoungeConfig(List.of("Front 1", "Front 2", "Institucional 1", "Institucional Vereadores")),
                new LoungeAreaResolver(areaRepository),
                "http://localhost:3000",
                72
        );
    }

    private Area area(String name) {
        var area = new Area(name, name, true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("facePhoto", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }
}
