package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceService;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessEventControllerTests {

    @Test
    void findAllWithoutQueryParamsUsesPagedSearchDefaults() {
        var service = mock(AccessEventService.class);
        when(service.search(any())).thenReturn(new AccessEventPageResponse(List.of(), 0, 50, 0, 0));
        var controller = new AccessEventController(service);

        var response = controller.findAll(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );

        assertThat(response).isInstanceOf(AccessEventPageResponse.class);
        var page = (AccessEventPageResponse) response;
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        verify(service).search(new AccessEventSearchRequest(
                0, 50, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        ));
        verify(service, never()).findAll();
    }

    @Test
    void exportCsvUsesFiltersAndReturnsAttachment() {
        var service = mock(AccessEventService.class);
        when(service.exportCsv(any())).thenReturn("horário,pessoa\r\n2026-06-10T18:00:00Z,Visitante\r\n");
        var controller = new AccessEventController(service);

        var response = controller.exportCsv(
                Instant.parse("2026-06-10T18:00:00Z"),
                Instant.parse("2026-06-11T07:00:00Z"),
                "Visitante",
                "529.982.247-25",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                null,
                null,
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                RecognitionStatus.RECOGNIZED,
                PassageStatus.PASSED,
                ReleaseMethod.FACIAL_RECOGNITION,
                "INTELBRAS_REAL",
                false,
                500
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("eventos-acesso-");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("horário,pessoa");
        verify(service).exportCsv(new AccessEventSearchRequest(
                0,
                500,
                Instant.parse("2026-06-10T18:00:00Z"),
                Instant.parse("2026-06-11T07:00:00Z"),
                "Visitante",
                "529.982.247-25",
                LocalDate.of(2026, 6, 10),
                "Front 1",
                null,
                null,
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                RecognitionStatus.RECOGNIZED,
                PassageStatus.PASSED,
                ReleaseMethod.FACIAL_RECOGNITION,
                "INTELBRAS_REAL",
                false
        ));
    }

    @Test
    void exportCsvIncludesOperationalColumnsAndCapsRows() {
        var repository = mock(AccessEventRepository.class);
        var service = new AccessEventService(
                repository,
                mock(DeviceService.class),
                mock(ApplicationEventPublisher.class),
                mock(AuditService.class),
                mock(RealtimePublisherService.class),
                mock(AccessMetricsService.class),
                new IntelbrasProperties()
        );
        var area = new Area("Entrada VIP", "Portaria", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("Catraca Front", "Intelbras SS 5531 MF W", "SERIAL", "192.168.15.5",
                "Entrada", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        var event = new AccessEvent(
                PersonType.GUEST,
                UUID.randomUUID(),
                "Visitante CSV",
                "52998224725",
                "52998224725",
                "Visitante CSV",
                device,
                area,
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                Instant.parse("2026-06-10T18:00:00Z"),
                "INTELBRAS_REAL",
                Map.of("operatorName", "Operador Um")
        );
        event.applyPersonSnapshot("Visitante CSV", "52998224725", "visitante@empresa.local", "81999990000",
                LocalDate.of(2026, 6, 10), "Front 2");
        event.applyOperationalFields(EventCategory.ACCESS_DECISION, RecognitionStatus.RECOGNIZED, PassageStatus.PASSED,
                ReleaseMethod.FACIAL_RECOGNITION, UUID.randomUUID(), "Ajuste manual", "Face", "1", "reader",
                "rec-1", "Liberado", Instant.parse("2026-06-10T18:00:00Z"));
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<AccessEvent>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        var csv = service.exportCsv(new AccessEventSearchRequest(
                0, 50_000, null, null, "Visitante", null, null, "Front 2", null, null,
                null, null, null, null, null, null, null
        ));

        assertThat(csv).contains("horário", "pessoa", "CPF", "método/liberação", "operador", "motivo manual");
        assertThat(csv).contains("\"Visitante CSV\"", "\"52998224725\"", "\"Front 2\"", "\"Catraca Front\"",
                "\"Entrada VIP\"", "\"FACIAL_RECOGNITION\"", "\"Operador Um\"", "\"Ajuste manual\"");
        verify(repository).findAll(org.mockito.ArgumentMatchers.<Specification<AccessEvent>>any(),
                argThat((Pageable pageable) -> pageable.getPageSize() == 10_000));
    }
}
