package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.events.AccessEventService;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public IntelbrasEventImportService(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                       IntelbrasProperties properties, IntelbrasEventMapper eventMapper, AccessEventService accessEventService,
                                       IntelbrasPersonResolver personResolver) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.properties = properties;
        this.eventMapper = eventMapper;
        this.personResolver = personResolver;
        this.accessEventService = accessEventService;
    }

    @Transactional
    public IntelbrasEventImportResult importAccessControlEvents(UUID deviceId) {
        var connection = connectionService.connectionFor(deviceId);
        return importAccessControlEvents(connection);
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
        for (var connection : connections) {
            var result = importAccessControlEvents(connection);
            received += result.received();
            imported += result.imported();
            skipped += result.skipped();
        }
        log.info("events_imported devices={} received={} imported={} skipped={}",
                connections.size(), received, imported, skipped);
        return new IntelbrasEventImportResult(null, received, imported, skipped, connections.size());
    }

    private IntelbrasEventImportResult importAccessControlEvents(IntelbrasDeviceConnection connection) {
        if (!connection.configured()) {
            throw new IllegalArgumentException("Intelbras device credentials are not configured.");
        }
        var records = cgiClient.findAccessControlEvents(connection.host(), connection.username(), connection.password());
        log.info("events_found device_id={} count={}", connection.device().getId(), records.size());
        var imported = 0;
        var skipped = 0;
        for (Map<String, Object> record : records) {
            var parsed = eventMapper.parseAccessControlCardRec(record);
            var normalized = eventMapper.normalizeAccessControlCardRec(record, connection.device(), personResolver.resolve(connection.device(), parsed));
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
}
