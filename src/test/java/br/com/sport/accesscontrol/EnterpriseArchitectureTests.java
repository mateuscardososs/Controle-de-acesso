package br.com.sport.accesscontrol;

import br.com.sport.accesscontrol.audit.AuditLog;
import br.com.sport.accesscontrol.audit.AuditLogRepository;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.RequestContext;
import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.config.RabbitMqConfig;
import br.com.sport.accesscontrol.config.WebSocketConfig;
import br.com.sport.accesscontrol.employees.Employee;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.*;
import br.com.sport.accesscontrol.integration.intelbras.scheduler.IntelbrasSyncScheduler;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasIntegrationService;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasAccessEventSimulatorRequest;
import br.com.sport.accesscontrol.integration.intelbras.simulator.IntelbrasSimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnterpriseArchitectureTests {

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
}
