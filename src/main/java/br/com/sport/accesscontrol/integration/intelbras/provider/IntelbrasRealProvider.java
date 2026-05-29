package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.integration.provider.ProviderDeviceStatus;
import br.com.sport.accesscontrol.integration.provider.ProviderPermission;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import br.com.sport.accesscontrol.metrics.AccessMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "app.intelbras", name = "mode", havingValue = "real")
public class IntelbrasRealProvider implements AccessControlProvider {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasRealProvider.class);

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasCgiClient cgiClient;
    private final IntelbrasFaceEncoder faceEncoder;
    private final IntelbrasEventMapper eventMapper;
    private final IntelbrasProperties properties;
    private final AccessMetricsService accessMetricsService;

    public IntelbrasRealProvider(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                 IntelbrasFaceEncoder faceEncoder,
                                 IntelbrasEventMapper eventMapper,
                                 IntelbrasProperties properties,
                                 AccessMetricsService accessMetricsService) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.faceEncoder = faceEncoder;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.accessMetricsService = accessMetricsService;
    }

    @Override
    public ProviderSyncResult syncPerson(ProviderPerson person) {
        var start = Instant.now();
        if (!person.active()) {
            return removePerson(person);
        }
        java.util.List<br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection> connections;
        if (person.allowedAreaIds() != null && !person.allowedAreaIds().isEmpty()) {
            connections = connectionService.selectOnlineConfiguredDevicesForAreas(person.allowedAreaIds());
            if (connections.isEmpty()) {
                return result(start, ProviderSyncStatus.FAILED,
                        "Nenhuma controladora online para as áreas permitidas");
            }
        } else if (person.areaId() != null) {
            var selectedConnection = connectionService.selectOnlineConfiguredDevice(person.areaId());
            if (selectedConnection.isEmpty()) {
                return result(start, ProviderSyncStatus.FAILED, "Nenhuma controladora online para as áreas permitidas");
            }
            connections = java.util.List.of(selectedConnection.get());
        } else {
            return result(start, ProviderSyncStatus.FAILED, noAllowedAreasMessage(person));
        }

        log.info("manual_sync_target_devices person_type={} person_id={} selected_devices_count={} device_ids=[{}] device_hosts=[{}]",
                person.personType(), person.personId(), connections.size(),
                connections.stream().map(c -> c.device().getId() == null ? "null" : c.device().getId().toString()).collect(Collectors.joining(",")),
                connections.stream().map(c -> IntelbrasHttpSupport.maskHost(c.host())).collect(Collectors.joining(",")));
        log.info("SYNC_DEVICES_SELECTED person_type={} person_id={} count={}",
                person.personType(), person.personId(), connections.size());
        log.info("intelbras_sync_start person_type={} person_id={} devices_count={}",
                person.personType(), person.personId(), connections.size());

        String photoData = null;
        try {
            photoData = faceEncoder.toJpegBase64(person.facePhotoUrl());
        } catch (IllegalArgumentException exception) {
            log.warn("intelbras_real_face_payload_unavailable_user_only person_type={} person_id={} error={}",
                    person.personType(), person.personId(), safe(exception.getMessage()));
        }

        var errors = new ArrayList<String>();
        var synced = 0;
        for (IntelbrasDeviceConnection connection : connections) {
            var requestStart = Instant.now();
            try {
                var identity = resolveIdentity(person);
                log.info("intelbras_identity_strategy strategy={} user_id={} card_no={} person_type={} person_id={} document_present={}",
                        identity.strategy().name().toLowerCase(java.util.Locale.ROOT), identity.userId(), identity.cardNo(),
                        person.personType(), person.personId(), person.document() != null && !person.document().isBlank());
                log.info("intelbras_real_sync_person_start protocol=CGI device_id={} host={} person_type={} person_id={} user_id={} card_no={} photo_base64_bytes={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()), person.personType(),
                        person.personId(), identity.userId(), identity.cardNo(), photoData == null ? 0 : photoData.length());
                var validFrom = localDeviceTime(person.validFrom(), LocalDateTime.now(properties.zoneId()).minusDays(1));
                var validUntil = localDeviceTime(person.validUntil(), LocalDateTime.of(2037, 12, 31, 23, 59, 59));
                log.info("intelbras_real_sync_person_validity device_id={} person_type={} person_id={} user_id={} valid_from={} valid_until={} timezone={}",
                        connection.device().getId(), person.personType(), person.personId(), identity.userId(),
                        validFrom, validUntil, properties.getTimezone());
                log.info("SYNC_DEVICE_ATTEMPT device_id={} host={} person_type={} person_id={} user_id={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), identity.userId());
                log.info("intelbras_sync_device_attempt device_id={} host={} person_type={} person_id={} user_id={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), identity.userId());
                cgiClient.upsertAccessUser(
                        connection.host(),
                        connection.username(),
                        connection.password(),
                        identity.userId(),
                        identity.cardNo(),
                        person.fullName(),
                        validFrom,
                        validUntil
                );
                if (photoData != null) {
                    try {
                        cgiClient.replaceFace(connection.host(), connection.username(), connection.password(), identity.userId(),
                                person.fullName(), photoData);
                    } catch (Exception faceException) {
                        log.warn("intelbras_real_face_sync_failed_user_kept device_id={} host={} person_type={} person_id={} user_id={} error={}",
                                connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()), person.personType(),
                                person.personId(), identity.userId(), safe(faceException.getMessage()));
                    }
                }
                synced++;
                accessMetricsService.recordControllerRequest(connection.device(), "sync_person", true,
                        Duration.between(requestStart, Instant.now()));
                log.info("intelbras_real_sync_person_success protocol=CGI device_id={} person_type={} person_id={} user_id={}",
                        connection.device().getId(), person.personType(), person.personId(), identity.userId());
                log.info("SYNC_DEVICE_SUCCESS device_id={} host={} person_type={} person_id={} user_id={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), identity.userId());
                log.info("intelbras_sync_device_success device_id={} host={} person_type={} person_id={} user_id={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), identity.userId());
            } catch (Exception exception) {
                accessMetricsService.recordControllerRequest(connection.device(), "sync_person", false,
                        Duration.between(requestStart, Instant.now()));
                accessMetricsService.recordControllerCommunicationFailure(connection.device());
                errors.add("Falha ao sincronizar na controladora " + deviceLabel(connection) + ": " + safe(exception.getMessage()));
                log.warn("intelbras_real_sync_person_failed device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
                log.warn("SYNC_DEVICE_FAILED device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
                log.warn("intelbras_sync_device_failed device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
            }
        }
        if (synced == connections.size()) {
            return result(start, ProviderSyncStatus.SUCCESS, "Sincronizado em " + synced + " controladora(s).");
        }
        if (synced > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Sincronizado parcialmente: " + synced + " de " + connections.size() + " controladoras. "
                            + String.join("; ", errors));
        }
        return result(start, ProviderSyncStatus.FAILED,
                errors.isEmpty()
                        ? "Falha ao sincronizar nas controladoras permitidas."
                        : String.join("; ", errors));
    }

    @Override
    public ProviderSyncResult removePerson(ProviderPerson person) {
        var start = Instant.now();
        var connections = connectionService.allConfiguredDevices();
        if (connections.isEmpty()) {
            return result(start, ProviderSyncStatus.FAILED, "No configured Intelbras real devices found.");
        }

        var removed = 0;
        for (IntelbrasDeviceConnection connection : connections) {
            var requestStart = Instant.now();
            try {
                var identity = resolveIdentity(person);
                log.info("intelbras_identity_strategy strategy={} user_id={} card_no={} person_type={} person_id={} document_present={} operation=remove",
                        identity.strategy().name().toLowerCase(java.util.Locale.ROOT), identity.userId(), identity.cardNo(),
                        person.personType(), person.personId(), person.document() != null && !person.document().isBlank());
                cgiClient.removeFace(connection.host(), connection.username(), connection.password(), identity.userId());
                cgiClient.removeAccessUser(connection.host(), connection.username(), connection.password(), identity.userId());
                removed++;
                accessMetricsService.recordControllerRequest(connection.device(), "remove_person", true,
                        Duration.between(requestStart, Instant.now()));
            } catch (Exception exception) {
                accessMetricsService.recordControllerRequest(connection.device(), "remove_person", false,
                        Duration.between(requestStart, Instant.now()));
                accessMetricsService.recordControllerCommunicationFailure(connection.device());
                log.warn("intelbras_real_remove_person_failed device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
            }
        }
        if (removed == connections.size()) {
            return result(start, ProviderSyncStatus.SUCCESS, "Intelbras real remove success for " + removed + " device(s).");
        }
        if (removed > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Intelbras real remove partial: " + removed + "/" + connections.size() + " device(s).");
        }
        return result(start, ProviderSyncStatus.FAILED, "Intelbras real remove failed for all devices.");
    }

    @Override
    public ProviderSyncResult updatePermission(ProviderPermission permission) {
        var start = Instant.now();
        var connections = connectionService.allConfiguredDevices();
        if (connections.isEmpty()) {
            return result(start, ProviderSyncStatus.FAILED, "No configured Intelbras real devices found.");
        }
        return result(start, ProviderSyncStatus.SUCCESS,
                "Permission update accepted; Intelbras user validity is applied during person sync.");
    }

    @Override
    public ProviderDeviceStatus fetchDeviceStatus(UUID deviceId) {
        var connection = connectionService.connectionFor(deviceId);
        if (!connection.configured()) {
            return new ProviderDeviceStatus(deviceId, DeviceStatus.OFFLINE, Instant.now(),
                    "Credenciais Intelbras não configuradas para este dispositivo.");
        }
        var requestStart = Instant.now();
        try {
            var deviceType = cgiClient.getDeviceType(connection.host(), connection.username(), connection.password());
            var serial = cgiClient.getSerialNo(connection.host(), connection.username(), connection.password());
            accessMetricsService.recordControllerRequest(connection.device(), "fetch_status", true,
                    Duration.between(requestStart, Instant.now()));
            return new ProviderDeviceStatus(deviceId, DeviceStatus.ONLINE, Instant.now(),
                    "Intelbras " + nullToBlank(deviceType) + " serial=" + nullToBlank(serial));
        } catch (Exception exception) {
            accessMetricsService.recordControllerRequest(connection.device(), "fetch_status", false,
                    Duration.between(requestStart, Instant.now()));
            log.warn("intelbras_real_fetch_device_status_failed device_id={} host={} error={}",
                    deviceId, IntelbrasHttpSupport.maskHost(connection.host()), safe(exception.getMessage()));
            return new ProviderDeviceStatus(deviceId, DeviceStatus.OFFLINE, Instant.now(), safe(exception.getMessage()));
        }
    }

    @Override
    public NormalizedAccessEvent normalizeAccessEvent(Map<String, Object> payload) {
        return eventMapper.normalizeAccessControlCardRec(payload, null, null);
    }

    private IntelbrasIdentityCodec.IntelbrasIdentity resolveIdentity(ProviderPerson person) {
        var identity = IntelbrasIdentityCodec.resolve(properties.getIdentityStrategy(), person.personType(),
                person.personId(), person.document());
        var cardNo = digits(person.cardNo());
        if (!cardNo.isBlank()) {
            return new IntelbrasIdentityCodec.IntelbrasIdentity(identity.strategy(), identity.userId(), cardNo);
        }
        return identity;
    }

    private LocalDateTime localDeviceTime(Instant instant, LocalDateTime fallback) {
        return instant == null ? fallback : LocalDateTime.ofInstant(instant, properties.zoneId());
    }

    private ProviderSyncResult result(Instant start, ProviderSyncStatus status, String message) {
        return new ProviderSyncResult(status, safe(message), Duration.between(start, Instant.now()));
    }

    private String noAllowedAreasMessage(ProviderPerson person) {
        return person.personType() == PersonType.GUEST
                ? "Visitante sem áreas permitidas"
                : "Pessoa sem áreas permitidas";
    }

    private String deviceLabel(IntelbrasDeviceConnection connection) {
        var device = connection.device();
        if (device.getName() != null && !device.getName().isBlank()) {
            return device.getName();
        }
        return device.getId() == null ? "desconhecida" : device.getId().toString();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}", "$1.*.*")
                .replaceAll("(?i)(password|senha)=([^\\s,;]+)", "$1=***");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
