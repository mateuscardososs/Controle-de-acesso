package br.com.sport.accesscontrol.metrics;

import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.events.AccessEvent;
import br.com.sport.accesscontrol.events.AccessResult;
import br.com.sport.accesscontrol.events.PassageStatus;
import br.com.sport.accesscontrol.events.RecognitionStatus;
import br.com.sport.accesscontrol.events.ReleaseMethod;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AccessMetricsService {

    private final MeterRegistry meterRegistry;
    private final Map<UUID, AtomicInteger> controllerOnlineStatus = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> controllerLastSuccess = new ConcurrentHashMap<>();

    public AccessMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordAccessEvent(AccessEvent event) {
        var tags = accessTags(event);
        counter("access_events", "Eventos de acesso salvos", tags).increment();

        if (event.getAccessResult() == AccessResult.ALLOWED) {
            counter("access_events_allowed", "Eventos de acesso liberados", tags).increment();
        } else if (event.getAccessResult() == AccessResult.DENIED) {
            counter("access_events_denied", "Eventos de acesso negados", tags).increment();
        } else if (event.getAccessResult() == AccessResult.ERROR) {
            counter("access_events_error", "Eventos de acesso com erro", tags).increment();
        }

        if (event.getReleaseMethod() == ReleaseMethod.MANUAL_ADMIN_RELEASE) {
            counter("manual_admin_release", "Liberacoes manuais administrativas", tags).increment();
        }

        if (event.getRecognitionStatus() == RecognitionStatus.RECOGNIZED) {
            counter("recognition_success", "Reconhecimentos bem-sucedidos", tags).increment();
        } else if (event.getRecognitionStatus() == RecognitionStatus.NOT_RECOGNIZED
                || event.getRecognitionStatus() == RecognitionStatus.ERROR) {
            counter("recognition_failure", "Falhas de reconhecimento", tags).increment();
        }

        if (event.getPassageStatus() == PassageStatus.PASSED) {
            counter("passage_confirmed", "Passagens confirmadas", tags).increment();
        } else if (event.getPassageStatus() == PassageStatus.NOT_PASSED
                || event.getPassageStatus() == PassageStatus.ERROR) {
            counter("passage_not_confirmed", "Passagens nao confirmadas", tags).increment();
        }
    }

    public void recordControllerOnline(Device device) {
        controllerStatusGauge(device).set(1);
        controllerLastSuccessGauge(device).set(Instant.now().getEpochSecond());
    }

    public void recordControllerOffline(Device device) {
        controllerStatusGauge(device).set(0);
    }

    public void recordControllerCommunicationFailure(Device device) {
        counter("controller_communication_failures", "Falhas de comunicacao com controladoras", controllerTags(device)).increment();
        recordControllerOffline(device);
    }

    public void recordControllerRequest(Device device, String operation, boolean success, Duration duration) {
        Timer.builder("controller_request_duration")
                .description("Duracao das chamadas para controladoras")
                .tags(controllerTags(device))
                .tag("operation", safe(operation))
                .tag("result", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration == null ? Duration.ZERO : duration);
        if (success) {
            recordControllerOnline(device);
        }
    }

    private Counter counter(String name, String description, Tags tags) {
        return Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry);
    }

    private Tags accessTags(AccessEvent event) {
        // Tags must stay low-cardinality. Never add CPF, name, email, photo URLs, biometric data or raw payload values.
        return Tags.of(
                "result", enumName(event.getAccessResult()),
                "event_type", enumName(event.getEventType()),
                "origin", safe(event.getOrigin()),
                "release_method", enumName(event.getReleaseMethod()),
                "device_id", event.getDevice() == null ? "unknown" : event.getDevice().getId().toString(),
                "device_name", event.getDevice() == null ? "unknown" : safe(event.getDevice().getName())
        );
    }

    private Tags controllerTags(Device device) {
        return Tags.of(
                "device_id", device == null ? "unknown" : device.getId().toString(),
                "device_name", device == null ? "unknown" : safe(device.getName()),
                "controller_ip", device == null ? "unknown" : safe(device.getIpAddress())
        );
    }

    private AtomicInteger controllerStatusGauge(Device device) {
        return controllerOnlineStatus.computeIfAbsent(device.getId(), ignored -> {
            var value = new AtomicInteger(device.getOnlineStatus() == DeviceStatus.ONLINE ? 1 : 0);
            io.micrometer.core.instrument.Gauge.builder("controller_online_status", value, AtomicInteger::get)
                    .description("Status da controladora: 1 online, 0 offline")
                    .tags(controllerTags(device))
                    .register(meterRegistry);
            return value;
        });
    }

    private AtomicLong controllerLastSuccessGauge(Device device) {
        return controllerLastSuccess.computeIfAbsent(device.getId(), ignored -> {
            var value = new AtomicLong(0);
            io.micrometer.core.instrument.Gauge.builder("controller_last_success_timestamp", value, AtomicLong::get)
                    .description("Unix timestamp da ultima comunicacao bem-sucedida com a controladora")
                    .tags(controllerTags(device))
                    .register(meterRegistry);
            return value;
        });
    }

    private String enumName(Enum<?> value) {
        return value == null ? "unknown" : value.name();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
