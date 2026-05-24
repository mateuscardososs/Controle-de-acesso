package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.devices.DeviceHealthService;
import br.com.sport.accesscontrol.events.AccessEventService;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class IntelbrasEventImportService {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasEventImportService.class);

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasCgiClient cgiClient;
    private final IntelbrasProperties properties;
    private final IntelbrasEventMapper eventMapper;
    private final IntelbrasPersonResolver personResolver;
    private final AccessEventService accessEventService;
    private final AccessMetricsService accessMetricsService;
    private final DeviceHealthService deviceHealthService;

    public IntelbrasEventImportService(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                       IntelbrasProperties properties, IntelbrasEventMapper eventMapper, AccessEventService accessEventService,
                                       IntelbrasPersonResolver personResolver, AccessMetricsService accessMetricsService,
                                       DeviceHealthService deviceHealthService) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.properties = properties;
        this.eventMapper = eventMapper;
        this.personResolver = personResolver;
        this.accessEventService = accessEventService;
        this.accessMetricsService = accessMetricsService;
        this.deviceHealthService = deviceHealthService;
    }

    @Transactional
    public IntelbrasEventImportResult importAccessControlEvents(UUID deviceId) {
        var connection = connectionService.connectionFor(deviceId);
        try {
            return importAccessControlEvents(connection);
        } catch (RuntimeException exception) {
            deviceHealthService.communicationFailure(connection.device().getId(), exception.getMessage());
            throw exception;
        }
    }

    @Transactional
    public IntelbrasEventImportResult importOnlineAccessControlEvents() {
        if (properties.getMode() != IntelbrasProperties.Mode.REAL) {
            log.info("event_polling_started enabled=false reason=mode_not_real mode={}", properties.getMode());
            return new IntelbrasEventImportResult(null, 0, 0, 0, 0);
        }
        var connections = connectionService.onlineConfiguredDevices();
        log.info("event_polling_started devices={}", connections.size());

        var received = 0;
        var imported = 0;
        var skipped = 0;
        var failed = 0;
        for (var connection : connections) {
            try {
                var result = importAccessControlEvents(connection);
                received += result.received();
                imported += result.imported();
                skipped += result.skipped();
            } catch (RuntimeException exception) {
                failed++;
                deviceHealthService.communicationFailure(connection.device().getId(), exception.getMessage());
                log.warn("events_import_failed device_id={} host={} error={}",
                        connection.device().getId(), br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport.maskHost(connection.host()),
                        exception.getMessage());
            }
        }
        log.info("events_imported devices={} received={} imported={} skipped={} failed_devices={}",
                connections.size(), received, imported, skipped, failed);
        return new IntelbrasEventImportResult(null, received, imported, skipped, connections.size());
    }

    private IntelbrasEventImportResult importAccessControlEvents(IntelbrasDeviceConnection connection) {
        if (!connection.configured()) {
            throw new IllegalArgumentException("Intelbras device credentials are not configured.");
        }
        var requestStart = Instant.now();
        java.util.List<Map<String, Object>> records;
        try {
            records = cgiClient.findAccessControlEvents(connection.host(), connection.username(), connection.password());
            accessMetricsService.recordControllerRequest(connection.device(), "import_access_events", true,
                    Duration.between(requestStart, Instant.now()));
            deviceHealthService.heartbeat(connection.device().getId());
        } catch (RuntimeException exception) {
            accessMetricsService.recordControllerRequest(connection.device(), "import_access_events", false,
                    Duration.between(requestStart, Instant.now()));
            throw exception;
        }
        log.info("events_found device_id={} count={}", connection.device().getId(), records.size());
        var imported = 0;
        var skipped = 0;
        for (Map<String, Object> record : records) {
            var parsed = eventMapper.parseAccessControlCardRec(record);
            var identity = personResolver.resolve(connection.device(), parsed);
            if (!identity.foundInDatabase()) {
                log.info("event_unresolved_person deviceId={} rawUserId={} rawCardNo={} rawCardName={}",
                        connection.device().getId(), parsed.userId(), rawText(record, "CardNo"), parsed.cardName());
            }
            var normalized = eventMapper.normalizeAccessControlCardRec(record, connection.device(), identity);
            if (accessEventService.recordImported(normalized).isPresent()) {
                imported++;
                log.info("events_imported device_id={} rec_no={} user_id={} status={} method={} door={}",
                        connection.device().getId(), parsed.recNo(), parsed.userId(), parsed.status(), parsed.method(), parsed.door());
            } else {
                skipped++;
                log.info("events_skipped_duplicate device_id={} rec_no={} user_id={} create_time={} door={}",
                        connection.device().getId(), parsed.recNo(), parsed.userId(), parsed.createTime(), parsed.door());
            }
        }
        return new IntelbrasEventImportResult(connection.device().getId(), records.size(), imported, skipped);
    }

    private String rawText(Map<String, Object> payload, String key) {
        var value = payload == null ? null : payload.get(key);
        return value == null ? null : value.toString();
    }
}
