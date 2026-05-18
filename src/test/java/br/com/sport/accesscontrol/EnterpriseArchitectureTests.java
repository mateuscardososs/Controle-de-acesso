package br.com.sport.accesscontrol;

import br.com.sport.accesscontrol.audit.AuditLog;
import br.com.sport.accesscontrol.audit.AuditLogRepository;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.RequestContext;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.config.WebSocketConfig;
import br.com.sport.accesscontrol.devices.*;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.*;
import br.com.sport.accesscontrol.guests.*;
import br.com.sport.accesscontrol.integration.intelbras.scheduler.IntelbrasSyncScheduler;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasAccessEventSimulatorRequest;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasSimulatorService;
import br.com.sport.accesscontrol.mail.MailDeliveryResult;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.realtime.RealtimeAccessEventMapper;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Queue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnterpriseArchitectureTests {

    @TempDir
    Path tempDir;

    @Test
    void rabbitTopologyDeclaresDurableQueuesWithDlq() {
        var config = new RabbitMqConfig();

        Queue employeeQueue = config.employeeSyncQueue();
        Queue accessEventQueue = config.accessEventQueue();
        Queue auditQueue = config.auditQueue();

        assertThat(employeeQueue.getName()).isEqualTo("employee.sync.queue");
        assertThat(accessEventQueue.getName()).isEqualTo("access.event.queue");
        assertThat(auditQueue.getName()).isEqualTo("audit.queue");
        assertThat(employeeQueue.getArguments()).containsEntry("x-dead-letter-exchange", "access-control.dlx");
    }

    @Test
    void websocketTopicsAreStable() {
        assertThat(WebSocketConfig.ACCESS_EVENTS_TOPIC).isEqualTo("/topic/access-events");
        assertThat(WebSocketConfig.DEVICE_STATUS_TOPIC).isEqualTo("/topic/device-status");
        assertThat(WebSocketConfig.SYSTEM_ALERTS_TOPIC).isEqualTo("/topic/system-alerts");
    }

    @Test
    void auditServicePersistsAndPublishesAuditEvent() {
        var repository = mock(AuditLogRepository.class);
        var requestContext = mock(RequestContext.class);
        var publisher = mock(IntegrationEventPublisher.class);
        when(requestContext.actorIp()).thenReturn("127.0.0.1");
        when(requestContext.correlationId()).thenReturn("corr-1");
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog auditLog = invocation.getArgument(0);
            ReflectionTestUtils.setField(auditLog, "id", UUID.randomUUID());
            return auditLog;
        });

        var service = new AuditService(repository, requestContext, publisher);
        service.record("DEVICE_CREATED", "Device", UUID.randomUUID(), Map.of(), Map.of(), Map.of());

        verify(repository).save(any(AuditLog.class));
        verify(publisher).publishAudit(any());
    }

    @Test
    void schedulerAttemptsSyncForOnlineDevices() {
        var deviceService = mock(br.com.sport.accesscontrol.devices.DeviceService.class);
        var integrationService = mock(IntelbrasIntegrationService.class);
        var scheduler = new IntelbrasSyncScheduler(deviceService, integrationService);
        when(deviceService.findOnlineDevices()).thenReturn(List.of());

        scheduler.synchronizeOnlineDevices();

        verify(deviceService).findOnlineDevices();
        verifyNoInteractions(integrationService);
    }

    @Test
    void simulatorTranslatesCpfIntoAccessEventSimulation() {
        var employeeRepository = mock(EmployeeRepository.class);
        var accessEventService = mock(AccessEventService.class);
        var employee = new Employee("Leao", "123", null, null, null, null, null, null, null);
        var employeeId = UUID.randomUUID();
        ReflectionTestUtils.setField(employee, "id", employeeId);
        when(employeeRepository.findByCpf("123")).thenReturn(Optional.of(employee));
        when(accessEventService.simulate(any(AccessEventSimulationRequest.class))).thenReturn(null);

        var service = new IntelbrasSimulatorService(employeeRepository, accessEventService);
        service.simulate(new IntelbrasAccessEventSimulatorRequest(
                "123",
                UUID.randomUUID(),
                AccessEventType.ENTRY,
                AccessResult.ALLOWED
        ));

        verify(accessEventService).simulate(argThat(request ->
                request.personId().equals(employeeId)
                        && request.origin().equals("INTELBRAS_SIMULATOR")
                        && request.accessResult() == AccessResult.ALLOWED));
    }

    @Test
    void realtimeAccessEventMessageIncludesEnrichedFields() {
        var employeeRepository = mock(EmployeeRepository.class);
        var employee = new Employee("Leao", "123", null, null, null, null, null, null, null);
        var employeeId = UUID.randomUUID();
        ReflectionTestUtils.setField(employee, "id", employeeId);
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        var area = area();
        var device = device(area);
        var event = accessEvent(employeeId, device, area);
        var mapper = new RealtimeAccessEventMapper(employeeRepository);

        var message = mapper.toMessage(event);

        assertThat(message.id()).isEqualTo(event.getId());
        assertThat(message.personName()).isEqualTo("Leao");
        assertThat(message.personCpf()).isEqualTo("123");
        assertThat(message.deviceName()).isEqualTo("Catraca Social");
        assertThat(message.areaName()).isEqualTo("Social");
        assertThat(message.accessResult()).isEqualTo(AccessResult.ALLOWED);
    }

    @Test
    void realtimeAccessEventMapperFallsBackWhenRelationsAreMissing() {
        var employeeRepository = mock(EmployeeRepository.class);
        var personId = UUID.randomUUID();
        when(employeeRepository.findById(personId)).thenReturn(Optional.empty());
        var event = new AccessEvent(
                br.com.sport.accesscontrol.common.PersonType.EMPLOYEE,
                personId,
                null,
                null,
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                Instant.now(),
                "TEST",
                Map.of()
        );
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "createdAt", Instant.now());

        var message = new RealtimeAccessEventMapper(employeeRepository).toMessage(event);

        assertThat(message.personName()).isNull();
        assertThat(message.deviceId()).isNull();
        assertThat(message.areaId()).isNull();
    }

    @Test
    void accessEventSimulationPublishesRichRealtimeMessageAndKeepsDomainEvent() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var area = area();
        var device = device(area);
        var personId = UUID.randomUUID();
        when(deviceService.getById(device.getId())).thenReturn(device);
        when(repository.save(any(AccessEvent.class))).thenAnswer(invocation -> {
            AccessEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(event, "createdAt", Instant.now());
            return event;
        });

        var service = new AccessEventService(repository, deviceService, eventPublisher, auditService, realtimePublisher);
        service.simulate(new AccessEventSimulationRequest(
                br.com.sport.accesscontrol.common.PersonType.EMPLOYEE,
                personId,
                device.getId(),
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                Instant.now(),
                "TEST",
                Map.of()
        ));

        verify(realtimePublisher).publishAccessEvent(any(AccessEvent.class));
        verify(eventPublisher).publishEvent(any(br.com.sport.accesscontrol.common.events.AccessEventReceivedEvent.class));
        verify(auditService).record(eq("ACCESS_EVENT_RECEIVED"), eq("AccessEvent"), any(), any(), any(), any());
    }

    @Test
    void deviceStatusChangePublishesRealtimeStatus() {
        var repository = mock(DeviceRepository.class);
        var areaService = mock(br.com.sport.accesscontrol.areas.AreaService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var device = device(area());
        when(repository.findById(device.getId())).thenReturn(Optional.of(device));

        var service = new DeviceService(repository, areaService, eventPublisher, auditService, realtimePublisher);
        service.updateStatus(device.getId(), new DeviceStatusRequest(DeviceStatus.ONLINE));

        verify(realtimePublisher).publishDeviceStatus(eq(device), eq("Device status changed"));
        verify(eventPublisher).publishEvent(any(br.com.sport.accesscontrol.common.events.DeviceStatusChangedEvent.class));
    }

    @Test
    void guestWorkflowCreatesInviteAndPublishesRealtimeAlert() {
        var guestRepository = mock(GuestRepository.class);
        var inviteRepository = mock(GuestInviteRepository.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
            return guest;
        });
        when(inviteRepository.save(any(GuestInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var mailService = mock(MailService.class);
        when(mailService.sendGuestInvite(any(), any())).thenReturn(MailDeliveryResult.skipped("disabled"));
        var service = new GuestService(guestRepository, inviteRepository, mock(FaceStorageService.class),
                auditService, realtimePublisher, mailService, "http://localhost:3000", 72);
        var response = service.create(guestRequest());

        assertThat(response.status()).isEqualTo(GuestStatus.PENDING_REGISTRATION);
        assertThat(response.inviteToken()).isNotBlank();
        assertThat(response.inviteUrl()).contains("/guest-registration/");
        assertThat(response.emailDeliveryStatus()).isEqualTo("SKIPPED");
        verify(mailService).sendGuestInvite(any(), any());
        verify(auditService).record(eq("GUEST_CREATED"), eq("Guest"), any(), any(), any(), any());
        verify(realtimePublisher).publishSystemAlert(any());
    }

    @Test
    void guestInviteResendReturnsInviteUrlAndRecordsMailFailureWithoutBreakingFlow() {
        var guest = guest();
        var guestRepository = mock(GuestRepository.class);
        var inviteRepository = mock(GuestInviteRepository.class);
        var mailService = mock(MailService.class);
        when(guestRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(inviteRepository.save(any(GuestInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mailService.sendGuestInviteResent(eq(guest), any())).thenReturn(MailDeliveryResult.failed("smtp down"));
        var service = new GuestService(guestRepository, inviteRepository, mock(FaceStorageService.class),
                mock(AuditService.class), mock(RealtimePublisherService.class), mailService,
                "http://localhost:3000", 72);

        var response = service.resendInvite(guest.getId());

        assertThat(response.inviteUrl()).contains("/guest-registration/");
        assertThat(response.emailDeliveryStatus()).isEqualTo("FAILED");
        verify(mailService).sendGuestInviteResent(eq(guest), any());
    }

    @Test
    void guestRegistrationRejectsInvalidToken() {
        var inviteRepository = mock(GuestInviteRepository.class);
        when(inviteRepository.findByToken("missing")).thenReturn(Optional.empty());
        var service = new GuestService(mock(GuestRepository.class), inviteRepository, mock(FaceStorageService.class),
                mock(AuditService.class), mock(RealtimePublisherService.class), mock(MailService.class),
                "http://localhost:3000", 72);

        assertThatThrownBy(() -> service.publicRegistration("missing"))
                .isInstanceOf(br.com.sport.accesscontrol.common.ResourceNotFoundException.class);
    }

    @Test
    void guestRegistrationCompletesWithFaceUploadAuditAndRealtime() {
        var guest = guest();
        var invite = new GuestInvite(guest, "token", Instant.now().plusSeconds(3600));
        var inviteRepository = mock(GuestInviteRepository.class);
        var faceStorage = mock(FaceStorageService.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var mailService = mock(MailService.class);
        when(inviteRepository.findByToken("token")).thenReturn(Optional.of(invite));
        when(faceStorage.store(any(), eq(guest.getId()))).thenReturn("/uploads/faces/photo.png");
        when(mailService.sendGuestRegistrationCompleted(guest)).thenReturn(MailDeliveryResult.delivered());
        var service = new GuestService(mock(GuestRepository.class), inviteRepository, faceStorage, auditService,
                realtimePublisher, mailService, "http://localhost:3000", 72);

        var response = service.completeRegistration("token", "81999990000", "Sport", png());

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.facePhotoUrl()).isEqualTo("/uploads/faces/photo.png");
        assertThat(invite.getUsedAt()).isNotNull();
        verify(auditService).record(eq("GUEST_FACE_UPLOADED"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(auditService).record(eq("GUEST_REGISTRATION_COMPLETED"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(mailService).sendGuestRegistrationCompleted(guest);
        verify(realtimePublisher).publishSystemAlert(any());
    }

    @Test
    void guestCancellationAndExpirationAuditAndPublishRealtime() {
        var guest = guest();
        var guestRepository = mock(GuestRepository.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        when(guestRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(guestRepository.findByStatusNotAndVisitEndBefore(eq(GuestStatus.CANCELLED), any()))
                .thenReturn(List.of(guest()));
        var service = new GuestService(guestRepository, mock(GuestInviteRepository.class), mock(FaceStorageService.class),
                auditService, realtimePublisher, mock(MailService.class), "http://localhost:3000", 72);

        var cancelled = service.cancel(guest.getId());
        var expired = service.expireOverdueGuests();

        assertThat(cancelled.status()).isEqualTo(GuestStatus.CANCELLED);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).status()).isEqualTo(GuestStatus.EXPIRED);
        verify(auditService).record(eq("GUEST_CANCELLED"), eq("Guest"), any(), any(), any(), any());
        verify(auditService).record(eq("GUEST_EXPIRED"), eq("Guest"), any(), any(), any(), any());
        verify(realtimePublisher, atLeast(2)).publishSystemAlert(any());
    }

    @Test
    void faceStorageAcceptsValidImageAndRejectsInvalidUpload() {
        var storage = new FaceStorageService(tempDir.toString());
        var guestId = UUID.randomUUID();

        var stored = storage.store(png(), guestId);

        assertThat(stored).startsWith("/uploads/faces/" + guestId);
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("facePhoto", "bad.txt", "text/plain", "bad".getBytes()), guestId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Area area() {
        var area = new Area("Social", "Acesso social", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        return area;
    }

    private Device device(Area area) {
        var device = new Device(
                "Catraca Social",
                "Intelbras",
                "ABC",
                "192.168.0.10",
                "Portaria",
                DeviceOperationType.ENTRY_EXIT,
                DeviceStatus.UNKNOWN,
                area
        );
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        return device;
    }

    private AccessEvent accessEvent(UUID employeeId, Device device, Area area) {
        var event = new AccessEvent(
                br.com.sport.accesscontrol.common.PersonType.EMPLOYEE,
                employeeId,
                device,
                area,
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                Instant.now(),
                "TEST",
                Map.of("source", "unit-test")
        );
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "createdAt", Instant.now());
        return event;
    }

    private GuestDtos.GuestRequest guestRequest() {
        return new GuestDtos.GuestRequest(
                "Visitante",
                "123",
                "visitante@sport.com.br",
                "81999990000",
                "Sport",
                "Reuniao",
                "Host",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                null
        );
    }

    private Guest guest() {
        var guest = new Guest("Visitante", "123", "visitante@sport.com.br", null, "Sport", "Reuniao",
                "Host", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        return guest;
    }

    private MockMultipartFile png() {
        byte[] bytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53, (byte) 0xDE,
                0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
                0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00,
                0x18, (byte) 0xDD, (byte) 0x8D, (byte) 0xB0,
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
        return new MockMultipartFile("facePhoto", "face.png", "image/png", bytes);
    }
}
