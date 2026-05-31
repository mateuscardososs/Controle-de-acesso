package br.com.sport.accesscontrol;

import br.com.sport.accesscontrol.audit.AuditLog;
import br.com.sport.accesscontrol.audit.AuditLogRepository;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.RequestContext;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.config.WebSocketConfig;
import br.com.sport.accesscontrol.dashboard.DashboardService;
import br.com.sport.accesscontrol.devices.*;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.employees.EmployeeRequest;
import br.com.sport.accesscontrol.employees.EmployeeService;
import br.com.sport.accesscontrol.employees.EmployeeStatus;
import br.com.sport.accesscontrol.events.*;
import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.guests.*;
import br.com.sport.accesscontrol.integration.intelbras.scheduler.IntelbrasSyncScheduler;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasPersonResolver;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasAccessEventSimulatorRequest;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasSimulatorService;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.ProviderDeviceStatus;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.integration.sync.*;
import br.com.sport.accesscontrol.mail.MailDeliveryResult;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import br.com.sport.accesscontrol.realtime.RealtimeAccessEventMapper;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import br.com.sport.accesscontrol.auth.AdminUserSeeder;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        var actorId = UUID.randomUUID();
        when(requestContext.actorIp()).thenReturn("127.0.0.1");
        when(requestContext.correlationId()).thenReturn("corr-1");
        when(requestContext.actorUserId()).thenReturn(Optional.of(actorId));
        when(requestContext.actorMetadata()).thenReturn(Map.of(
                "actorEmail", "admin@empresa.local",
                "actorName", "Administrador",
                "actorRoles", List.of("ROLE_ADMIN")
        ));
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog auditLog = invocation.getArgument(0);
            ReflectionTestUtils.setField(auditLog, "id", UUID.randomUUID());
            return auditLog;
        });

        var service = new AuditService(repository, requestContext, publisher);
        service.record("DEVICE_CREATED", "Device", UUID.randomUUID(), Map.of(), Map.of(), Map.of());

        verify(repository).save(argThat(auditLog ->
                actorId.equals(auditLog.getActorUserId())
                        && "admin@empresa.local".equals(auditLog.getDetails().get("actorEmail"))
                        && auditLog.getDetails().containsKey("actorRoles")
        ));
        verify(publisher).publishAudit(any());
    }

    @Test
    void adminSeederDoesNotCreateDefaultAdminInProductionByDefault() {
        var repository = mock(UserRepository.class);
        var passwordEncoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        environment.setActiveProfiles("prod");
        when(repository.findByEmailIgnoreCase("admin@empresa.local")).thenReturn(Optional.empty());

        var seeder = new AdminUserSeeder(repository, passwordEncoder, environment);
        seeder.run(null);

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void adminSeederCreatesAdminInDevelopmentWhenEnabled() {
        var repository = mock(UserRepository.class);
        var passwordEncoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment()
                .withProperty("APP_SEED_ADMIN_ENABLED", "true")
                .withProperty("APP_SEED_ADMIN_EMAIL", "dev-admin@empresa.local")
                .withProperty("APP_SEED_ADMIN_PASSWORD", "DevAdmin@123456");
        when(repository.existsByEmailIgnoreCase("dev-admin@empresa.local")).thenReturn(false);

        var seeder = new AdminUserSeeder(repository, passwordEncoder, environment);
        seeder.run(null);

        verify(repository).save(argThat(user ->
                "dev-admin@empresa.local".equals(user.getEmail())
                        && user.getRole() == UserRole.ADMIN
                        && passwordEncoder.matches("DevAdmin@123456", user.getPasswordHash())
        ));
    }

    @Test
    void adminSeederRejectsDefaultAdminPasswordInProduction() {
        var repository = mock(UserRepository.class);
        var passwordEncoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var defaultAdmin = new User("Administrador", "admin@empresa.local",
                passwordEncoder.encode("Admin@123456"), UserRole.ADMIN, true);
        ReflectionTestUtils.setField(defaultAdmin, "id", UUID.randomUUID());
        when(repository.findByEmailIgnoreCase("admin@empresa.local")).thenReturn(Optional.of(defaultAdmin));

        var seeder = new AdminUserSeeder(repository, passwordEncoder, environment);

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default admin credentials");
    }

    @Test
    void schedulerSkipsHealthCheckInFakeMode() {
        var connectionService = mock(IntelbrasDeviceConnectionService.class);
        var integrationService = mock(IntelbrasIntegrationService.class);
        var properties = new IntelbrasProperties(); // default mode = FAKE
        var scheduler = new IntelbrasSyncScheduler(connectionService, integrationService, properties);

        scheduler.synchronizeConfiguredDevices();

        verifyNoInteractions(connectionService);
        verifyNoInteractions(integrationService);
    }

    @Test
    void schedulerKeepsProcessingDevicesWhenOneFails() {
        var connectionService = mock(IntelbrasDeviceConnectionService.class);
        var integrationService = mock(IntelbrasIntegrationService.class);
        var first = device(area());
        var second = device(area());
        var conn1 = new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection(first, "192.168.1.10", "admin", "pass");
        var conn2 = new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection(second, "192.168.1.11", "admin", "pass");
        when(connectionService.allConfiguredDevices()).thenReturn(List.of(conn1, conn2));
        doThrow(new RuntimeException("timeout")).when(integrationService).synchronizeDevice(first);
        var properties = new IntelbrasProperties();
        properties.setMode(IntelbrasProperties.Mode.REAL);
        var scheduler = new IntelbrasSyncScheduler(connectionService, integrationService, properties);

        scheduler.synchronizeConfiguredDevices();

        verify(integrationService).synchronizeDevice(first);
        verify(integrationService).synchronizeDevice(second);
    }

    @Test
    void deviceStatusEndpointModelIncludesOperationalFields() {
        var area = area();
        var device = device(area);
        device.markHeartbeat();
        device.registerCommunicationFailure("timeout");

        var response = DeviceStatusResponse.from(device);

        assertThat(response.deviceId()).isEqualTo(device.getId());
        assertThat(response.controllerIp()).isEqualTo("192.168.0.10");
        assertThat(response.lastSuccessAt()).isNotNull();
        assertThat(response.lastFailureAt()).isNotNull();
        assertThat(response.lastError()).isEqualTo("timeout");
        assertThat(response.areaName()).isEqualTo("Social");
        assertThat(response.active()).isTrue();
    }

    @Test
    void intelbrasStatusFailureUpdatesDeviceHealthAndMetric() {
        var provider = mock(AccessControlProvider.class);
        var deviceService = mock(DeviceService.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var registry = new SimpleMeterRegistry();
        var device = device(area());
        when(provider.fetchDeviceStatus(device.getId()))
                .thenReturn(new ProviderDeviceStatus(device.getId(), DeviceStatus.OFFLINE, Instant.now(), "timeout"));
        when(deviceService.getById(device.getId())).thenReturn(device);

        var healthService = new DeviceHealthService(deviceService, auditService, realtimePublisher,
                new AccessMetricsService(registry));
        var service = new IntelbrasIntegrationService(provider, healthService);

        service.synchronizeDevice(device);

        assertThat(device.getLastFailureAt()).isNotNull();
        assertThat(device.getLastError()).isEqualTo("timeout");
        assertThat(registry.get("controller_communication_failures").counter().count()).isEqualTo(1);
        verify(auditService).record(eq("DEVICE_COMMUNICATION_FAILURE"), eq("Device"), eq(device.getId()), any(), any(), any());
    }

    @Test
    void intelbrasStatusExceptionDoesNotEscapeDeviceSync() {
        var provider = mock(AccessControlProvider.class);
        var deviceService = mock(DeviceService.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var device = device(area());
        when(provider.fetchDeviceStatus(device.getId())).thenThrow(new RuntimeException("network down"));
        when(deviceService.getById(device.getId())).thenReturn(device);

        var healthService = new DeviceHealthService(deviceService, auditService, realtimePublisher,
                new AccessMetricsService(new SimpleMeterRegistry()));
        var service = new IntelbrasIntegrationService(provider, healthService);

        service.synchronizeDevice(device);

        assertThat(device.getLastFailureAt()).isNotNull();
        assertThat(device.getLastError()).isEqualTo("network down");
    }

    @Test
    void simulatorTranslatesCpfIntoAccessEventSimulation() {
        var employeeRepository = mock(EmployeeRepository.class);
        var accessEventService = mock(AccessEventService.class);
        var employee = new Employee("Colaborador", "123", null, null, null, null, null,
                Instant.now(), Instant.now().plusSeconds(3600));
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
        var employee = new Employee("Colaborador", "123", null, null, null, null, null, null, null);
        var employeeId = UUID.randomUUID();
        ReflectionTestUtils.setField(employee, "id", employeeId);
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        var area = area();
        var device = device(area);
        var event = accessEvent(employeeId, device, area);
        var mapper = new RealtimeAccessEventMapper(employeeRepository, mock(GuestRepository.class));

        var message = mapper.toMessage(event);

        assertThat(message.id()).isEqualTo(event.getId());
        assertThat(message.personName()).isEqualTo("Colaborador");
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

        var message = new RealtimeAccessEventMapper(employeeRepository, mock(GuestRepository.class)).toMessage(event);

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

        var registry = new SimpleMeterRegistry();
        var service = new AccessEventService(repository, deviceService, eventPublisher, auditService, realtimePublisher,
                new AccessMetricsService(registry), new IntelbrasProperties());
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
        assertThat(registry.get("access_events").counter().count()).isEqualTo(1);
        assertThat(registry.get("access_events_allowed").counter().count()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void accessEventSearchReturnsPagedEnvelopeAndUsesFilters() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var area = area();
        var device = device(area);
        var event = accessEvent(UUID.randomUUID(), device, area);
        ReflectionTestUtils.setField(event, "personName", "Maria Operadora");
        ReflectionTestUtils.setField(event, "personCpf", "12345678901");
        event.applyOperationalFields(EventCategory.ACCESS_DECISION, RecognitionStatus.RECOGNIZED,
                PassageStatus.PASSED, ReleaseMethod.FACIAL_RECOGNITION, null, null,
                "Face", "1", "1", "42", "0", event.getEventTime());
        when(repository.findAll(org.mockito.Mockito.<Specification<AccessEvent>>any(), org.mockito.Mockito.<Pageable>any()))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1));

        var service = new AccessEventService(repository, deviceService, eventPublisher, auditService, realtimePublisher,
                new AccessMetricsService(new SimpleMeterRegistry()), new IntelbrasProperties());
        var response = service.search(new AccessEventSearchRequest(
                0,
                10,
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
                "Maria",
                "123.456.789-01",
                device.getId(),
                area.getId(),
                AccessEventType.ENTRY,
                AccessResult.ALLOWED,
                RecognitionStatus.RECOGNIZED,
                PassageStatus.PASSED,
                ReleaseMethod.FACIAL_RECOGNITION,
                "TEST",
                false
        ));

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(1);
        verify(repository).findAll(
                org.mockito.Mockito.<Specification<AccessEvent>>any(),
                org.mockito.Mockito.<Pageable>argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 10)
        );
    }

    @Test
    void manualReleasePersistsOperatorAndOperationalFields() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var area = area();
        var device = device(area);
        when(deviceService.getById(device.getId())).thenReturn(device);
        when(repository.save(any(AccessEvent.class))).thenAnswer(invocation -> {
            AccessEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(event, "createdAt", Instant.now());
            return event;
        });
        var operator = user("Operador", "operador@empresa.local", UserRole.HR);
        var authentication = new UsernamePasswordAuthenticationToken(new UserPrincipal(operator), null, List.of());

        var registry = new SimpleMeterRegistry();
        var service = new AccessEventService(repository, deviceService, eventPublisher, auditService, realtimePublisher,
                new AccessMetricsService(registry), new IntelbrasProperties());
        var response = service.manualRelease(new ManualAccessReleaseRequest(
                "Visitante Sem Face",
                "123.456.789-01",
                device.getId(),
                "Reconhecimento facial falhou",
                "Conferido em lista fisica"
        ), authentication);

        assertThat(response.eventType()).isEqualTo(AccessEventType.MANUAL_ADMIN_RELEASE);
        assertThat(response.accessResult()).isEqualTo(AccessResult.ALLOWED);
        assertThat(response.releaseMethod()).isEqualTo(ReleaseMethod.MANUAL_ADMIN_RELEASE);
        assertThat(response.eventCategory()).isEqualTo(EventCategory.MANUAL_RELEASE);
        assertThat(response.operatorUserId()).isEqualTo(operator.getId());
        assertThat(response.personCpf()).isEqualTo("12345678901");
        assertThat(response.manualReason()).isEqualTo("Reconhecimento facial falhou");
        assertThat(response.rawPayload()).containsEntry("operatorEmail", "operador@empresa.local");
        verify(realtimePublisher).publishAccessEvent(any(AccessEvent.class));
        verify(eventPublisher).publishEvent(any(br.com.sport.accesscontrol.common.events.AccessEventReceivedEvent.class));
        verify(auditService).record(eq("MANUAL_ADMIN_RELEASE"), eq("AccessEvent"), any(), any(), any(), any());
        assertThat(registry.get("manual_admin_release").counter().count()).isEqualTo(1);
        assertThat(registry.get("passage_confirmed").counter().count()).isEqualTo(1);
    }

    @Test
    void accessMetricsDoNotUsePersonalDataAsTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AccessMetricsService(registry);
        var area = area();
        var device = device(area);
        var event = accessEvent(UUID.randomUUID(), device, area);
        ReflectionTestUtils.setField(event, "personName", "Maria Visitante");
        ReflectionTestUtils.setField(event, "personCpf", "12345678901");
        event.applyOperationalFields(EventCategory.ACCESS_DECISION, RecognitionStatus.RECOGNIZED,
                PassageStatus.PASSED, ReleaseMethod.FACIAL_RECOGNITION, null, null,
                "Face", "1", "1", "42", "0", event.getEventTime());

        metrics.recordAccessEvent(event);

        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("Maria")
                        || tag.getValue().contains("12345678901")
                        || tag.getKey().equalsIgnoreCase("cpf")
                        || tag.getKey().equalsIgnoreCase("person_name"));
    }

    @Test
    void controllerCommunicationFailureIncrementsMetricAndMarksOfflineGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AccessMetricsService(registry);
        var device = device(area());

        metrics.recordControllerCommunicationFailure(device);

        assertThat(registry.get("controller_communication_failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("controller_online_status").gauge().value()).isZero();
    }

    @Test
    void intelbrasEventWithGuestUserIdResolvesGuestName() {
        var guest = new Guest("Visitante Real", "12345678901", "visitante@empresa.local", null, "Empresa", "Reuniao",
                "Host", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("12345678901")).thenReturn(Optional.of(guest));

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), intelbrasRecord("123.456.789-01", "Nome Intelbras"));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.GUEST);
        assertThat(identity.personId()).isEqualTo(guest.getId());
        assertThat(identity.personName()).isEqualTo("Visitante Real");
        assertThat(identity.personCpf()).isEqualTo("12345678901");
        assertThat(identity.foundInDatabase()).isTrue();
        verifyNoInteractions(employeeRepository);
    }

    @Test
    void intelbrasEventWithUserIdWithoutLeadingZeroResolvesGuestDocument() {
        var guest = new Guest("Visitante Real", "05731650411", "visitante@empresa.local", null, "Empresa", "Reuniao",
                "Host", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("5731650411")).thenReturn(Optional.empty());
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("05731650411")).thenReturn(Optional.of(guest));

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), intelbrasRecord("5731650411", "Nome Intelbras"));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.GUEST);
        assertThat(identity.personId()).isEqualTo(guest.getId());
        assertThat(identity.personName()).isEqualTo("Visitante Real");
        assertThat(identity.personCpf()).isEqualTo("05731650411");
        assertThat(identity.foundInDatabase()).isTrue();
    }

    @Test
    void intelbrasEventWithCardNoResolvesGuestDocumentWhenUserIdIsGenerated() {
        var guest = new Guest("Visitante Real", "05731650411", "visitante@empresa.local", "81999990000",
                "Empresa", "Reuniao", "Host", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("05731650411")).thenReturn(Optional.of(guest));

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord(
                        "1", "Visitante Real", "GABC123", "1", "15", "Entry", Instant.now(),
                        null, "0", "1", "1", Map.of("CardNo", "05731650411")
                ));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.GUEST);
        assertThat(identity.personId()).isEqualTo(guest.getId());
        assertThat(identity.personCpf()).isEqualTo("05731650411");
        assertThat(identity.foundInDatabase()).isTrue();
    }

    @Test
    void intelbrasEventWithEmployeeUserIdResolvesEmployeeName() {
        var employee = new Employee("Colaborador Real", "12345678901", null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("12345678901")).thenReturn(Optional.empty());
        when(employeeRepository.findByCpf("12345678901")).thenReturn(Optional.of(employee));

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), intelbrasRecord("12345678901", "Nome Intelbras"));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.EMPLOYEE);
        assertThat(identity.personId()).isEqualTo(employee.getId());
        assertThat(identity.personName()).isEqualTo("Colaborador Real");
        assertThat(identity.personCpf()).isEqualTo("12345678901");
        assertThat(identity.foundInDatabase()).isTrue();
    }

    @Test
    void intelbrasEventWithEmployeeCardNoResolvesEmployee() {
        var employee = new Employee("Colaborador Tag", "99999999999", "tag@empresa.local", null,
                null, "445566", null, UserRole.SECURITY_VIEWER, EmployeeStatus.ACTIVE, null, null);
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("445566")).thenReturn(Optional.empty());
        when(employeeRepository.findByCpf("445566")).thenReturn(Optional.empty());
        when(employeeRepository.findByCardNo("445566")).thenReturn(Optional.of(employee));

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord(
                        "1", "Colaborador Tag", "EABC123", "1", "15", "Entry", Instant.now(),
                        null, "0", "1", "1", Map.of("CardNo", "445566")
                ));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.EMPLOYEE);
        assertThat(identity.personId()).isEqualTo(employee.getId());
        assertThat(identity.personName()).isEqualTo("Colaborador Tag");
        assertThat(identity.foundInDatabase()).isTrue();
    }

    @Test
    void intelbrasEventWithoutDatabaseMatchUsesCardNameAndDoesNotCreateFakeEmployee() {
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        when(guestRepository.findFirstByCpfOrderByVisitStartDesc("12345678901")).thenReturn(Optional.empty());
        when(employeeRepository.findByCpf("12345678901")).thenReturn(Optional.empty());

        var identity = new IntelbrasPersonResolver(guestRepository, employeeRepository)
                .resolve(device(area()), intelbrasRecord("12345678901", "Visitante da Controladora"));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.UNKNOWN);
        assertThat(identity.personId()).isNull();
        assertThat(identity.personName()).isEqualTo("Visitante da Controladora");
        assertThat(identity.externalUserId()).isEqualTo("12345678901");
        assertThat(identity.foundInDatabase()).isFalse();
    }

    @Test
    void intelbrasEventWithoutUserIdOrCardNameUsesUnidentifiedUserLabel() {
        var identity = new IntelbrasPersonResolver(mock(GuestRepository.class), mock(EmployeeRepository.class))
                .resolve(device(area()), intelbrasRecord(null, null));

        assertThat(identity.personType()).isEqualTo(br.com.sport.accesscontrol.common.PersonType.UNKNOWN);
        assertThat(identity.personId()).isNull();
        assertThat(identity.personName()).isEqualTo("Usuário não identificado");
        assertThat(identity.foundInDatabase()).isFalse();
    }

    @Test
    void importedIntelbrasDuplicateByRecNoDoesNotCreateAccessEvent() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var device = device(area());
        when(deviceService.getById(device.getId())).thenReturn(device);
        when(repository.existsByDeviceIdAndOriginAndIntelbrasRecNo(device.getId(), "INTELBRAS_REAL", "77"))
                .thenReturn(true);

        var normalized = new IntelbrasEventMapper().normalizeAccessControlCardRec(
                Map.of(
                        "RecNo", "77",
                        "UserID", "12345678901",
                        "CardName", "Visitante",
                        "Status", "1",
                        "ErrorCode", "0",
                        "CreateTime", "2026-05-20 12:00:00",
                        "Door", "1",
                        "Method", "15"
                ),
                device,
                new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity(
                        br.com.sport.accesscontrol.common.PersonType.UNKNOWN,
                        null,
                        "Visitante",
                        null,
                        "12345678901",
                        "Visitante",
                        false
                )
        );

        var service = new AccessEventService(repository, deviceService, mock(ApplicationEventPublisher.class),
                mock(AuditService.class), mock(RealtimePublisherService.class),
                new AccessMetricsService(new SimpleMeterRegistry()), new IntelbrasProperties());

        assertThat(service.recordImported(normalized)).isEmpty();
        verify(repository, never()).save(any(AccessEvent.class));
    }

    @Test
    void importedIntelbrasDuplicateInsideDedupWindowDoesNotCreateAccessEvent() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var device = device(area());
        when(deviceService.getById(device.getId())).thenReturn(device);
        when(repository.existsByDeviceIdAndOriginAndIntelbrasDedupWindow(
                eq(device.getId()), eq("INTELBRAS_REAL"), any(), any(), eq("12345678901"), eq("1"), eq("15")
        )).thenReturn(true);

        var normalized = new IntelbrasEventMapper().normalizeAccessControlCardRec(
                Map.of(
                        "UserID", "12345678901",
                        "CardName", "Visitante",
                        "Status", "1",
                        "ErrorCode", "0",
                        "CreateTime", "2026-05-20 12:00:00",
                        "Door", "1",
                        "Method", "15"
                ),
                device,
                new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity(
                        br.com.sport.accesscontrol.common.PersonType.UNKNOWN,
                        null,
                        "Visitante",
                        null,
                        "12345678901",
                        "Visitante",
                        false
                )
        );

        var service = new AccessEventService(repository, deviceService, mock(ApplicationEventPublisher.class),
                mock(AuditService.class), mock(RealtimePublisherService.class),
                new AccessMetricsService(new SimpleMeterRegistry()), new IntelbrasProperties());

        assertThat(service.recordImported(normalized)).isEmpty();
        verify(repository, never()).save(any(AccessEvent.class));
    }

    @Test
    void importedIntelbrasEventEnrichesLegacyFront3GuestSnapshotAndPublishesRealtime() {
        var repository = mock(AccessEventRepository.class);
        var deviceService = mock(DeviceService.class);
        var realtimePublisher = mock(RealtimePublisherService.class);
        var guestRepository = mock(GuestRepository.class);
        var employeeRepository = mock(EmployeeRepository.class);
        var device = device(area());
        var invitedDay = java.time.LocalDate.of(2026, 6, 24);
        var guest = new Guest("Visitante Real", "05731650411", "visitante@empresa.local", "81999990000",
                "Empresa", "Reuniao", "Host", Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600), invitedDay, "Front 3");
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        when(deviceService.getById(device.getId())).thenReturn(device);
        when(guestRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(repository.save(any(AccessEvent.class))).thenAnswer(invocation -> {
            AccessEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(event, "createdAt", Instant.now());
            return event;
        });
        var normalized = new IntelbrasEventMapper().normalizeAccessControlCardRec(
                Map.of(
                        "RecNo", "88",
                        "UserID", "05731650411",
                        "CardNo", "05731650411",
                        "CardName", "Nome Intelbras",
                        "Status", "1",
                        "ErrorCode", "0",
                        "CreateTime", "2026-05-20 12:00:00",
                        "Door", "1",
                        "Method", "Face"
                ),
                device,
                new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity(
                        br.com.sport.accesscontrol.common.PersonType.GUEST,
                        guest.getId(),
                        guest.getFullName(),
                        guest.getCpf(),
                        "05731650411",
                        "Nome Intelbras",
                        true
                )
        );
        var service = new AccessEventService(repository, deviceService, mock(ApplicationEventPublisher.class),
                mock(AuditService.class), realtimePublisher, new AccessMetricsService(new SimpleMeterRegistry()),
                new IntelbrasProperties(), guestRepository, employeeRepository);

        var response = service.recordImported(normalized);

        assertThat(response).isPresent();
        assertThat(response.get().personName()).isEqualTo("Visitante Real");
        assertThat(response.get().personCpf()).isEqualTo("05731650411");
        assertThat(response.get().personEmail()).isEqualTo("visitante@empresa.local");
        assertThat(response.get().personPhone()).isEqualTo("81999990000");
        assertThat(response.get().invitedDay()).isEqualTo(invitedDay);
        assertThat(response.get().invitedLounge()).isEqualTo("Front 3");
        verify(realtimePublisher).publishAccessEvent(argThat(event ->
                invitedDay.equals(event.getInvitedDay()) && "Front 3".equals(event.getInvitedLounge())));
    }

    @Test
    void dashboardServiceReturnsRecentAccessEvents() {
        var repository = mock(AccessEventRepository.class);
        var area = area();
        var device = device(area);
        var event = accessEvent(UUID.randomUUID(), device, area);
        when(repository.findAllByOrderByEventTimeDesc(any(Pageable.class))).thenReturn(List.of(event));
        var service = new DashboardService(mock(EmployeeRepository.class), mock(DeviceRepository.class), repository);

        var events = service.recentEvents(6);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().id()).isEqualTo(event.getId());
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
                auditService, realtimePublisher, mailService, mock(ApplicationEventPublisher.class),
                defaultLoungeConfig(), "http://localhost:3000", 72);
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
                mock(ApplicationEventPublisher.class), defaultLoungeConfig(), "http://localhost:3000", 72);

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
                mock(ApplicationEventPublisher.class), defaultLoungeConfig(), "http://localhost:3000", 72);

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
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var guestRepository = mock(GuestRepository.class);
        when(inviteRepository.findByToken("token")).thenReturn(Optional.of(invite));
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(guestRepository.save(guest)).thenReturn(guest);
        when(faceStorage.store(any(), eq(guest.getId()))).thenReturn("/uploads/faces/photo.png");
        when(mailService.sendGuestRegistrationCompleted(guest)).thenReturn(MailDeliveryResult.delivered());
        var service = new GuestService(guestRepository, inviteRepository, faceStorage, auditService,
                realtimePublisher, mailService, eventPublisher, defaultLoungeConfig(), "http://localhost:3000", 72);

        var response = service.completeRegistration("token", "81999990000", "Empresa", png());

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(response.facePhotoUrl()).isEqualTo("/uploads/faces/photo.png");
        assertThat(invite.getUsedAt()).isNotNull();
        verify(auditService).record(eq("GUEST_FACE_UPLOADED"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(auditService).record(eq("GUEST_REGISTRATION_COMPLETED"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(mailService).sendGuestRegistrationCompleted(guest);
        verify(realtimePublisher).publishSystemAlert(any());
        verify(eventPublisher).publishEvent(new GuestReadyForSyncEvent(guest.getId()));
    }

    @Test
    void publicVisitorRegistrationQueuesAutoSyncAndUsesInvitedDayWindow() {
        var guestRepository = mock(GuestRepository.class);
        var inviteRepository = mock(GuestInviteRepository.class);
        var faceStorage = mock(FaceStorageService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var savedGuest = new AtomicReference<Guest>();
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            if (guest.getId() == null) {
                ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
            }
            savedGuest.set(guest);
            return guest;
        });
        when(guestRepository.findByIdWithAllowedAreas(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(savedGuest.get()));
        when(inviteRepository.save(any(GuestInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(faceStorage.store(any(), any())).thenReturn("/uploads/faces/public.png");
        var service = new GuestService(guestRepository, inviteRepository, faceStorage, mock(AuditService.class),
                mock(RealtimePublisherService.class), mock(MailService.class), eventPublisher,
                defaultLoungeConfig(), "http://localhost:3000", 72);

        var response = service.publicVisitorRegistration(
                "Visitante Publico",
                "52998224725",
                "visitante.publico@empresa.local",
                "81999990000",
                null,
                null,
                null,
                LocalDate.of(2026, 6, 10),
                "Front 1",
                null,
                null,
                png()
        );

        var expectedStart = LocalDate.of(2026, 6, 10).atTime(15, 0).atZone(ZoneId.of("America/Recife")).toInstant();
        var expectedEnd = LocalDate.of(2026, 6, 11).atTime(4, 0).atZone(ZoneId.of("America/Recife")).toInstant();
        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        verify(guestRepository, atLeastOnce()).save(argThat((Guest guest) ->
                guest.getSyncStatus() == SyncStatus.PENDING_SYNC
                        && expectedStart.equals(guest.getVisitStart())
                        && expectedEnd.equals(guest.getVisitEnd())
        ));
        verify(eventPublisher).publishEvent(any(GuestReadyForSyncEvent.class));
    }

    @Test
    void guestAutoSyncAfterPublicCompletionSkipsWhenAlreadySyncing() {
        var guest = guest();
        guest.markSyncing();
        var invite = new GuestInvite(guest, "token", Instant.now().plusSeconds(3600));
        var inviteRepository = mock(GuestInviteRepository.class);
        var guestRepository = mock(GuestRepository.class);
        var faceStorage = mock(FaceStorageService.class);
        var mailService = mock(MailService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        when(inviteRepository.findByToken("token")).thenReturn(Optional.of(invite));
        when(faceStorage.store(any(), eq(guest.getId()))).thenReturn("/uploads/faces/photo.png");
        when(mailService.sendGuestRegistrationCompleted(guest)).thenReturn(MailDeliveryResult.delivered());
        var service = new GuestService(guestRepository, inviteRepository, faceStorage, mock(AuditService.class),
                mock(RealtimePublisherService.class), mailService, eventPublisher,
                defaultLoungeConfig(), "http://localhost:3000", 72);

        var response = service.completeRegistration("token", "81999990000", "Empresa", png());

        assertThat(response.status()).isEqualTo(GuestStatus.COMPLETED);
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.SYNCING);
        verify(guestRepository, never()).findByIdWithAllowedAreas(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void guestManualSyncMarksPendingAndPublishesReadyEvent() {
        var guest = guest();
        guest.completeRegistration("81999990000", "Empresa", "/uploads/faces/photo.png");
        guest.markSyncFailed("timeout");
        var guestRepository = mock(GuestRepository.class);
        var auditService = mock(AuditService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(guestRepository.save(guest)).thenReturn(guest);
        var service = new GuestService(guestRepository, mock(GuestInviteRepository.class), mock(FaceStorageService.class),
                auditService, mock(RealtimePublisherService.class), mock(MailService.class),
                eventPublisher, defaultLoungeConfig(), "http://localhost:3000", 72);

        var response = service.requestSync(guest.getId());

        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(guest.getLastSyncError()).isNull();
        verify(guestRepository).save(guest);
        verify(auditService).record(eq("GUEST_SYNC_REQUESTED"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(eventPublisher).publishEvent(new GuestReadyForSyncEvent(guest.getId()));
    }

    @Test
    void guestCleanupRemovesOnlyMatchingOldRecordsAndAudits() {
        var oldCancelled = guest();
        oldCancelled.cancel();
        ReflectionTestUtils.setField(oldCancelled, "createdAt", Instant.now().minusSeconds(40L * 24 * 3600));
        var recentCancelled = guest();
        recentCancelled.cancel();
        ReflectionTestUtils.setField(recentCancelled, "createdAt", Instant.now());
        var failed = guest();
        failed.markSyncFailed("erro");
        ReflectionTestUtils.setField(failed, "createdAt", Instant.now().minusSeconds(40L * 24 * 3600));
        var guestRepository = mock(GuestRepository.class);
        var inviteRepository = mock(GuestInviteRepository.class);
        var auditService = mock(AuditService.class);
        when(guestRepository.findAll()).thenReturn(List.of(oldCancelled, recentCancelled, failed));
        when(inviteRepository.findByGuestIn(any())).thenReturn(List.of());
        var service = new GuestService(guestRepository, inviteRepository, mock(FaceStorageService.class),
                auditService, mock(RealtimePublisherService.class), mock(MailService.class),
                mock(ApplicationEventPublisher.class), defaultLoungeConfig(), "http://localhost:3000", 72);

        var result = service.cleanup(new GuestDtos.GuestCleanupRequest(
                List.of(GuestStatus.CANCELLED),
                List.of(),
                30,
                false
        ));

        assertThat(result.removedCount()).isEqualTo(1);
        assertThat(result.message()).contains("removidos");
        verify(guestRepository).deleteAllInBatch(argThat((List<Guest> guests) ->
                guests.size() == 1 && guests.contains(oldCancelled)
        ));
        verify(auditService).record(eq("GUEST_CLEANUP"), eq("Guest"), isNull(), any(), any(), any());
    }

    @Test
    void guestCleanupModeRemovesRecentCancelledRecordsWithoutAgeFilter() {
        var cancelled = guest();
        cancelled.cancel();
        ReflectionTestUtils.setField(cancelled, "createdAt", Instant.now());
        var active = guest();
        var guestRepository = mock(GuestRepository.class);
        var inviteRepository = mock(GuestInviteRepository.class);
        when(guestRepository.findAll()).thenReturn(List.of(cancelled, active));
        when(inviteRepository.findByGuestIn(any())).thenReturn(List.of());
        var service = new GuestService(guestRepository, inviteRepository, mock(FaceStorageService.class),
                mock(AuditService.class), mock(RealtimePublisherService.class), mock(MailService.class),
                mock(ApplicationEventPublisher.class), defaultLoungeConfig(), "http://localhost:3000", 72);

        var result = service.cleanup(new GuestDtos.GuestCleanupRequest(
                GuestDtos.GuestCleanupMode.CANCELLED,
                null,
                null,
                0,
                false,
                null
        ));

        assertThat(result.removedCount()).isEqualTo(1);
        verify(guestRepository).deleteAllInBatch(argThat((List<Guest> guests) ->
                guests.size() == 1 && guests.contains(cancelled)
        ));
    }

    @Test
    void guestCleanupAllRequiresTypedConfirmation() {
        var service = new GuestService(mock(GuestRepository.class), mock(GuestInviteRepository.class),
                mock(FaceStorageService.class), mock(AuditService.class), mock(RealtimePublisherService.class),
                mock(MailService.class), mock(ApplicationEventPublisher.class), defaultLoungeConfig(), "http://localhost:3000", 72);

        assertThatThrownBy(() -> service.cleanup(new GuestDtos.GuestCleanupRequest(
                GuestDtos.GuestCleanupMode.ALL,
                null,
                null,
                0,
                false,
                "limpar"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIMPAR");
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
                auditService, realtimePublisher, mock(MailService.class), mock(ApplicationEventPublisher.class),
                defaultLoungeConfig(), "http://localhost:3000", 72);

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

    @Test
    void employeeCreateCreatesAdminUserWithBcryptRoleCardAndFortyFiveDayValidityWithoutAutoIntelbrasSync() {
        var employeeRepository = mock(EmployeeRepository.class);
        var userRepository = mock(UserRepository.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        when(userRepository.existsByEmailIgnoreCase("colaborador@empresa.local")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            if (employee.getId() == null) {
                ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
            }
            return employee;
        });
        var passwordEncoder = new BCryptPasswordEncoder();
        var service = new EmployeeService(employeeRepository, userRepository, passwordEncoder,
                mock(FaceStorageService.class), eventPublisher, mock(AuditService.class));

        var response = service.create(new EmployeeRequest(
                "Colaborador Admin",
                "529.982.247-25",
                "Colaborador@Empresa.Local",
                null,
                null,
                " 445566 ",
                null,
                "Senha@123",
                UserRole.HR,
                EmployeeStatus.ACTIVE,
                null,
                null
        ));

        assertThat(response.cpf()).isEqualTo("52998224725");
        assertThat(response.email()).isEqualTo("colaborador@empresa.local");
        assertThat(response.role()).isEqualTo(UserRole.HR);
        assertThat(response.cardNo()).isEqualTo("445566");
        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(response.accessValidFrom()).isNotNull();
        assertThat(response.accessValidUntil()).isNotNull();
        assertThat(Duration.between(response.accessValidFrom(), response.accessValidUntil()))
                .isEqualTo(Duration.ofDays(45));
        verify(userRepository).save(argThat(user ->
                user.getRole() == UserRole.HR
                        && passwordEncoder.matches("Senha@123", user.getPasswordHash())
        ));
        verify(eventPublisher).publishEvent(any(br.com.sport.accesscontrol.common.events.EmployeeCreatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(EmployeeReadyForSyncEvent.class));
    }

    @Test
    void employeeManualSyncPublishesReadyEvent() {
        var employee = new Employee("Colaborador", "12345678901", "colaborador@empresa.local", null,
                null, "445566", null, UserRole.ADMIN, EmployeeStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        var employeeRepository = mock(EmployeeRepository.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        when(employeeRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(employeeRepository.save(employee)).thenReturn(employee);
        var service = new EmployeeService(employeeRepository, mock(UserRepository.class), new BCryptPasswordEncoder(),
                mock(FaceStorageService.class), eventPublisher, mock(AuditService.class));

        var response = service.requestSync(employee.getId());

        assertThat(response.syncStatus()).isEqualTo(SyncStatus.PENDING_SYNC);
        verify(eventPublisher).publishEvent(new EmployeeReadyForSyncEvent(employee.getId()));
    }

    @Test
    void intelbrasSyncWorkerMarksEmployeeSyncedAndPublishesAuditAndRealtime() {
        var employee = new Employee("Colaborador", "123", null, null, null, null, null,
                Instant.now(), Instant.now().plusSeconds(3600));
        employee.setCardNo("445566");
        ReflectionTestUtils.setField(employee, "id", UUID.randomUUID());
        var employeeRepository = mock(EmployeeRepository.class);
        var provider = mock(AccessControlProvider.class);
        var auditService = mock(AuditService.class);
        var realtime = mock(IntegrationSyncRealtimePublisher.class);
        when(employeeRepository.findByIdWithAllowedAreas(employee.getId())).thenReturn(Optional.of(employee));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "ok", java.time.Duration.ofMillis(10)));

        worker(employeeRepository, mock(GuestRepository.class), provider, auditService, mock(IntegrationEventPublisher.class), realtime, 3)
                .process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.EMPLOYEE, employee.getId(), 1));

        assertThat(employee.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        verify(employeeRepository).save(employee);
        verify(provider).syncPerson(argThat(person ->
                "445566".equals(person.cardNo())
                        && person.validFrom().equals(employee.getAccessValidFrom())
                        && person.validUntil().equals(employee.getAccessValidUntil())
        ));
        verify(auditService).record(eq("INTELBRAS_SYNC_STARTED"), eq("EMPLOYEE"), eq(employee.getId()), any(), any(), any());
        verify(auditService).record(eq("INTELBRAS_SYNC_SUCCEEDED"), eq("EMPLOYEE"), eq(employee.getId()), any(), any(), any());
        verify(realtime).publish(eq(br.com.sport.accesscontrol.common.PersonType.EMPLOYEE), eq(employee.getId()), eq(SyncStatus.SYNCED), any());
    }

    @Test
    void intelbrasSyncWorkerRetriesThenSendsToDlqAfterMaxAttempts() {
        var guest = guest();
        guest.completeRegistration("81999990000", "Empresa", "/uploads/faces/photo.png");
        guest.replaceAllowedAreas(Set.of(area()));
        var guestRepository = mock(GuestRepository.class);
        var provider = mock(AccessControlProvider.class);
        var publisher = mock(IntegrationEventPublisher.class);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(ProviderSyncStatus.FAILED, "down", java.time.Duration.ofMillis(5)));

        var worker = worker(mock(EmployeeRepository.class), guestRepository, provider, mock(AuditService.class),
                publisher, mock(IntegrationSyncRealtimePublisher.class), 2);
        worker.process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNC_FAILED);
        verify(guestRepository, atLeastOnce()).save(guest);
        verify(publisher).publishIntelbrasSync(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 2));
        assertThatThrownBy(() -> worker.process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 2)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void intelbrasSyncWorkerProcessesValidGuestMessage() {
        var guest = guest();
        guest.completeRegistration("81999990000", "Empresa", "/uploads/faces/photo.png");
        guest.replaceAllowedAreas(Set.of(area()));
        var guestRepository = mock(GuestRepository.class);
        var provider = mock(AccessControlProvider.class);
        var realtime = mock(IntegrationSyncRealtimePublisher.class);
        var auditService = mock(AuditService.class);
        var mailService = mock(MailService.class);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "ok", java.time.Duration.ofMillis(5)));
        when(mailService.sendGuestAccessApproved(guest)).thenReturn(MailDeliveryResult.delivered());

        worker(mock(EmployeeRepository.class), guestRepository, provider, auditService,
                mock(IntegrationEventPublisher.class), realtime, mailService, 3)
                .process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 1));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(guest.getAccessApprovedEmailStatus()).isEqualTo("SENT");
        assertThat(guest.getAccessApprovedEmailSentAt()).isNotNull();
        verify(guestRepository, atLeastOnce()).save(guest);
        verify(provider).syncPerson(argThat(person ->
                person.validFrom().equals(guest.getVisitStart())
                        && person.validUntil().equals(guest.getVisitEnd())
        ));
        verify(mailService).sendGuestAccessApproved(guest);
        verify(auditService).record(eq("INTELBRAS_SYNC_SUCCEEDED"), eq("GUEST"), eq(guest.getId()), any(), any(), any());
        verify(auditService).record(eq("GUEST_ACCESS_APPROVAL_EMAIL_SENT"), eq("Guest"), eq(guest.getId()), any(), any(), any());
        verify(realtime).publish(eq(br.com.sport.accesscontrol.common.PersonType.GUEST), eq(guest.getId()), eq(SyncStatus.SYNCED), contains("Visitante"));
    }

    @Test
    void intelbrasSyncWorkerDoesNotSendDuplicateGuestApprovalEmail() {
        var guest = guest();
        guest.completeRegistration("81999990000", "Empresa", "/uploads/faces/photo.png");
        guest.replaceAllowedAreas(Set.of(area()));
        guest.markAccessApprovedEmail("SENT", "E-mail enviado.", true);
        var guestRepository = mock(GuestRepository.class);
        var provider = mock(AccessControlProvider.class);
        var mailService = mock(MailService.class);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));
        when(provider.syncPerson(any())).thenReturn(new ProviderSyncResult(ProviderSyncStatus.SUCCESS, "ok", java.time.Duration.ofMillis(5)));

        worker(mock(EmployeeRepository.class), guestRepository, provider, mock(AuditService.class),
                mock(IntegrationEventPublisher.class), mock(IntegrationSyncRealtimePublisher.class), mailService, 3)
                .process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 1));

        verifyNoInteractions(mailService);
    }

    @Test
    void intelbrasSyncWorkerSkipsDelayedRetryWhenGuestAlreadySynced() {
        var guest = guest();
        guest.completeRegistration("81999990000", "Empresa", "/uploads/faces/photo.png");
        guest.markSynced();
        var guestRepository = mock(GuestRepository.class);
        var provider = mock(AccessControlProvider.class);
        var publisher = mock(IntegrationEventPublisher.class);
        when(guestRepository.findByIdWithAllowedAreas(guest.getId())).thenReturn(Optional.of(guest));

        worker(mock(EmployeeRepository.class), guestRepository, provider, mock(AuditService.class),
                publisher, mock(IntegrationSyncRealtimePublisher.class), 3)
                .process(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, guest.getId(), 2));

        assertThat(guest.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        verifyNoInteractions(provider);
        verifyNoInteractions(publisher);
    }

    @Test
    void intelbrasSyncWorkerDropsInvalidMessageWithoutRetryLoop() {
        var provider = mock(AccessControlProvider.class);
        var publisher = mock(IntegrationEventPublisher.class);

        worker(mock(EmployeeRepository.class), mock(GuestRepository.class), provider, mock(AuditService.class),
                publisher, mock(IntegrationSyncRealtimePublisher.class), 3)
                .process(new IntelbrasSyncMessage(null, null, 0));

        verifyNoInteractions(provider);
        verifyNoInteractions(publisher);
    }

    @Test
    void intelbrasDeviceConnectionServiceAcceptsOnlineSs5531WithCredentialsAndArea() {
        var device = device(area());
        ReflectionTestUtils.setField(device, "model", "SS 5531 MF W");
        device.setIntelbrasUsername("admin");
        device.setIntelbrasPassword("secret");
        device.setStatus(DeviceStatus.ONLINE);
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));

        var selected = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties())
                .selectOnlineConfiguredDevice(device.getArea().getId());

        assertThat(selected).isPresent();
        assertThat(selected.get().device().getId()).isEqualTo(device.getId());
    }

    @Test
    void intelbrasDeviceConnectionServiceRejectsOnlineDeviceWithGenericModelAndNoPassword() {
        var device = device(area());
        ReflectionTestUtils.setField(device, "model", "Generic Controller");
        ReflectionTestUtils.setField(device, "name", "Catraca Social");
        device.setIntelbrasUsername("admin");
        // no password — must be rejected when model/name also doesn't match Intelbras patterns
        device.setStatus(DeviceStatus.ONLINE);
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(device));

        var selected = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties())
                .selectOnlineConfiguredDevice(null);

        assertThat(selected).isEmpty();
    }

    @Test
    void intelbrasDeviceConnectionServicePrefersDeviceFromRequestedArea() {
        var fallback = device(area());
        ReflectionTestUtils.setField(fallback, "model", "SS 5531 MF W");
        fallback.setIntelbrasUsername("admin");
        fallback.setIntelbrasPassword("secret");
        fallback.setStatus(DeviceStatus.ONLINE);
        var preferredArea = area();
        var preferred = device(preferredArea);
        ReflectionTestUtils.setField(preferred, "model", "SS 5541 MF W");
        preferred.setIntelbrasUsername("admin");
        preferred.setIntelbrasPassword("secret");
        preferred.setStatus(DeviceStatus.ONLINE);
        var repository = mock(DeviceRepository.class);
        when(repository.findAll()).thenReturn(List.of(fallback, preferred));

        var selected = new IntelbrasDeviceConnectionService(repository, new IntelbrasProperties())
                .selectOnlineConfiguredDevice(preferredArea.getId());

        assertThat(selected).isPresent();
        assertThat(selected.get().device().getId()).isEqualTo(preferred.getId());
    }

    @Test
    void manualRetryEndpointQueuesSyncMessageAndAudits() {
        var publisher = mock(IntegrationEventPublisher.class);
        var auditService = mock(AuditService.class);
        var id = UUID.randomUUID();
        var controller = new IntegrationRetryController(publisher, auditService);

        var response = controller.retry("guest", id);

        assertThat(response).containsEntry("status", "queued");
        verify(auditService).record(eq("INTELBRAS_SYNC_MANUAL_RETRY"), eq("GUEST"), eq(id), any(), any(), any());
        verify(publisher).publishIntelbrasSync(new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, id, 1));
    }

    @Test
    void intelbrasSyncPublisherSendsRawMessageToRabbit() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publisher = new IntegrationEventPublisher(rabbitTemplate,
                mock(br.com.sport.accesscontrol.guests.GuestRepository.class),
                mock(br.com.sport.accesscontrol.employees.EmployeeRepository.class));
        var id = UUID.randomUUID();
        var message = new IntelbrasSyncMessage(br.com.sport.accesscontrol.common.PersonType.GUEST, id, 1);

        publisher.publishIntelbrasSync(message);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE),
                eq("intelbras.sync.requested"),
                eq(message)
        );
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

    private User user(String name, String email, UserRole role) {
        var user = new User(name, email, "{noop}password", role, true);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord intelbrasRecord(String userId, String cardName) {
        return new br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasAccessControlCardRecord(
                "1",
                cardName,
                userId,
                "1",
                "15",
                "Entry",
                Instant.now(),
                null,
                "0",
                "1",
                "1",
                Map.of()
        );
    }

    private IntelbrasSyncWorker worker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                                       AccessControlProvider provider, AuditService auditService,
                                       IntegrationEventPublisher publisher, IntegrationSyncRealtimePublisher realtime,
                                       int maxAttempts) {
        return worker(employeeRepository, guestRepository, provider, auditService, publisher, realtime, mock(MailService.class), maxAttempts);
    }

    private IntelbrasSyncWorker worker(EmployeeRepository employeeRepository, GuestRepository guestRepository,
                                       AccessControlProvider provider, AuditService auditService,
                                       IntegrationEventPublisher publisher, IntegrationSyncRealtimePublisher realtime,
                                       MailService mailService, int maxAttempts) {
        var deviceRepository = mock(DeviceRepository.class);
        when(deviceRepository.findAll()).thenReturn(List.of());
        return new IntelbrasSyncWorker(employeeRepository, guestRepository, deviceRepository, provider, auditService, publisher, realtime,
                mock(RealtimePublisherService.class), mailService, new SimpleMeterRegistry(), maxAttempts);
    }

    private GuestDtos.GuestRequest guestRequest() {
        return new GuestDtos.GuestRequest(
                "Visitante",
                "52998224725",
                "visitante@empresa.local",
                "81999990000",
                "Empresa",
                "Reuniao",
                "Host",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                null
        );
    }

    private Guest guest() {
        var guest = new Guest("Visitante", "123", "visitante@empresa.local", null, "Empresa", "Reuniao",
                "Host", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(guest, "id", UUID.randomUUID());
        return guest;
    }

    private static LoungeConfig defaultLoungeConfig() {
        return new LoungeConfig(List.of("Front 1", "Front 2", "Institucional 1", "Institucional Vereadores"));
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
