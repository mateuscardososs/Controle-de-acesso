package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.events.AccessEventReceivedEvent;
import br.com.sport.accesscontrol.devices.DeviceService;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AccessEventService {

    private final AccessEventRepository accessEventRepository;
    private final DeviceService deviceService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RealtimePublisherService realtimePublisherService;

    public AccessEventService(AccessEventRepository accessEventRepository, DeviceService deviceService,
                              ApplicationEventPublisher eventPublisher, AuditService auditService,
                              RealtimePublisherService realtimePublisherService) {
        this.accessEventRepository = accessEventRepository;
        this.deviceService = deviceService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
    }

    @Transactional
    public AccessEventResponse simulate(AccessEventSimulationRequest request) {
        var device = deviceService.getById(request.deviceId());
        var accessEvent = new AccessEvent(
                request.personType(),
                request.personId(),
                device,
                device.getArea(),
                request.eventType(),
                request.accessResult(),
                request.eventTime(),
                request.origin(),
                request.rawPayload()
        );
        var saved = accessEventRepository.save(accessEvent);
        auditService.record("ACCESS_EVENT_RECEIVED", "AccessEvent", saved.getId(),
                Map.of("origin", saved.getOrigin(), "result", saved.getAccessResult()), Map.of(), Map.of("id", saved.getId()));
        realtimePublisherService.publishAccessEvent(saved);
        eventPublisher.publishEvent(new AccessEventReceivedEvent(saved.getId()));
        return AccessEventResponse.from(saved);
    }

    @Transactional
    public Optional<AccessEventResponse> recordImported(NormalizedAccessEvent normalized) {
        var device = deviceService.getById(normalized.deviceId());
        if (accessEventRepository.existsByDevice_IdAndPersonIdAndEventTimeAndOrigin(
                device.getId(),
                normalized.personId(),
                normalized.eventTime(),
                normalized.origin()
        )) {
            return Optional.empty();
        }
        var accessEvent = new AccessEvent(
                normalized.personType(),
                normalized.personId(),
                device,
                device.getArea(),
                normalized.eventType(),
                normalized.accessResult(),
                normalized.eventTime(),
                normalized.origin(),
                normalized.rawPayload()
        );
        var saved = accessEventRepository.save(accessEvent);
        auditService.record("ACCESS_EVENT_IMPORTED", "AccessEvent", saved.getId(),
                Map.of("origin", saved.getOrigin(), "result", saved.getAccessResult()), Map.of(), Map.of("id", saved.getId()));
        realtimePublisherService.publishAccessEvent(saved);
        eventPublisher.publishEvent(new AccessEventReceivedEvent(saved.getId()));
        return Optional.of(AccessEventResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public List<AccessEventResponse> findAll() {
        return accessEventRepository.findAll().stream().map(AccessEventResponse::from).toList();
    }
}
