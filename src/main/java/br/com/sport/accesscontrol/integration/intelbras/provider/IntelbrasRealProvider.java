package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpcClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpcSession;
import br.com.sport.accesscontrol.integration.intelbras.mapper.IntelbrasEventMapper;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasDeviceConnection;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasDeviceConnectionService;
import br.com.sport.accesscontrol.integration.intelbras.service.IntelbrasFaceEncoder;
import br.com.sport.accesscontrol.integration.provider.AccessControlProvider;
import br.com.sport.accesscontrol.integration.provider.NormalizedAccessEvent;
import br.com.sport.accesscontrol.integration.provider.ProviderDeviceStatus;
import br.com.sport.accesscontrol.integration.provider.ProviderPermission;
import br.com.sport.accesscontrol.integration.provider.ProviderPerson;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncResult;
import br.com.sport.accesscontrol.integration.provider.ProviderSyncStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.intelbras", name = "mode", havingValue = "real")
public class IntelbrasRealProvider implements AccessControlProvider {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasRealProvider.class);
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasRpcClient rpcClient;
    private final IntelbrasCgiClient cgiClient;
    private final IntelbrasFaceEncoder faceEncoder;
    private final IntelbrasEventMapper eventMapper;

    public IntelbrasRealProvider(IntelbrasDeviceConnectionService connectionService, IntelbrasRpcClient rpcClient,
                                 IntelbrasCgiClient cgiClient, IntelbrasFaceEncoder faceEncoder,
                                 IntelbrasEventMapper eventMapper) {
        this.connectionService = connectionService;
        this.rpcClient = rpcClient;
        this.cgiClient = cgiClient;
        this.faceEncoder = faceEncoder;
        this.eventMapper = eventMapper;
    }

    @Override
    public ProviderSyncResult syncPerson(ProviderPerson person) {
        var start = Instant.now();
        if (!person.active()) {
            return removePerson(person);
        }
        var connections = connectionService.allConfiguredDevices();
        if (connections.isEmpty()) {
            return result(start, ProviderSyncStatus.FAILED, "No configured Intelbras real devices found.");
        }

        final String photoData;
        try {
            photoData = faceEncoder.toJpegBase64(person.facePhotoUrl());
        } catch (IllegalArgumentException exception) {
            return result(start, ProviderSyncStatus.FAILED, exception.getMessage());
        }

        var errors = new ArrayList<String>();
        var synced = 0;
        for (IntelbrasDeviceConnection connection : connections) {
            try {
                var session = rpcClient.login(connection.host(), connection.username(), connection.password());
                ensureUser(connection, session, person);
                insertFace(connection, session, person, photoData);
                rpcClient.keepAlive(session);
                synced++;
                log.info("intelbras_real_sync_person device_id={} person_type={} person_id={}",
                        connection.device().getId(), person.personType(), person.personId());
            } catch (Exception exception) {
                errors.add("device=" + connection.device().getId() + " error=" + safe(exception.getMessage()));
                log.warn("intelbras_real_sync_person_failed device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
            }
        }
        if (synced == connections.size()) {
            return result(start, ProviderSyncStatus.SUCCESS, "Intelbras real sync success for " + synced + " device(s).");
        }
        if (synced > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Intelbras real sync partial: " + synced + "/" + connections.size() + " device(s).");
        }
        return result(start, ProviderSyncStatus.FAILED,
                "Intelbras real sync failed for all devices. " + String.join("; ", errors));
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
            try {
                var session = rpcClient.login(connection.host(), connection.username(), connection.password());
                var userId = userId(person);
                rpcClient.postRpc(connection.host(), session, "AccessFace.removeMulti", Map.of("UserIDs", List.of(userId)));
                rpcClient.postRpc(connection.host(), session, "AccessUser.removeMulti", Map.of("UserIDs", List.of(userId)));
                rpcClient.keepAlive(session);
                removed++;
            } catch (Exception exception) {
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
                    "Intelbras real device credentials are not configured.");
        }
        try {
            var deviceType = cgiClient.getDeviceType(connection.host(), connection.username(), connection.password());
            var serial = cgiClient.getSerialNo(connection.host(), connection.username(), connection.password());
            return new ProviderDeviceStatus(deviceId, DeviceStatus.ONLINE, Instant.now(),
                    "Intelbras " + nullToBlank(deviceType) + " serial=" + nullToBlank(serial));
        } catch (Exception exception) {
            log.warn("intelbras_real_fetch_device_status_failed device_id={} host={} error={}",
                    deviceId, IntelbrasHttpSupport.maskHost(connection.host()), safe(exception.getMessage()));
            return new ProviderDeviceStatus(deviceId, DeviceStatus.OFFLINE, Instant.now(), safe(exception.getMessage()));
        }
    }

    @Override
    public NormalizedAccessEvent normalizeAccessEvent(Map<String, Object> payload) {
        return eventMapper.normalizeAccessControlCardRec(payload, null, null);
    }

    private void ensureUser(IntelbrasDeviceConnection connection, IntelbrasRpcSession session, ProviderPerson person) {
        var token = startFindUser(connection, session, person);
        try {
            rpcClient.postRpc(connection.host(), session, "AccessUser.insertMulti",
                    Map.of("UserList", List.of(userPayload(person))));
        } catch (Exception exception) {
            log.debug("intelbras_real_user_insert_skipped device_id={} reason={}",
                    connection.device().getId(), safe(exception.getMessage()));
        } finally {
            if (token != null && !token.isBlank()) {
                stopFindUser(connection, session, token);
            }
        }
    }

    private String startFindUser(IntelbrasDeviceConnection connection, IntelbrasRpcSession session, ProviderPerson person) {
        try {
            var response = rpcClient.postRpc(connection.host(), session, "AccessUser.startFind", Map.of(
                    "Condition", Map.of("UserID", userId(person)),
                    "Count", 1
            ));
            return firstText(response, "/params/token", "/params/Token", "/params/findToken");
        } catch (Exception exception) {
            log.debug("intelbras_real_user_find_skipped device_id={} reason={}",
                    connection.device().getId(), safe(exception.getMessage()));
            return null;
        }
    }

    private void stopFindUser(IntelbrasDeviceConnection connection, IntelbrasRpcSession session, String token) {
        try {
            rpcClient.postRpc(connection.host(), session, "AccessUser.stopFind", Map.of("Token", token));
        } catch (Exception exception) {
            log.debug("intelbras_real_user_stop_find_skipped device_id={} reason={}",
                    connection.device().getId(), safe(exception.getMessage()));
        }
    }

    private void insertFace(IntelbrasDeviceConnection connection, IntelbrasRpcSession session, ProviderPerson person, String photoData) {
        var face = new LinkedHashMap<String, Object>();
        face.put("UserID", userId(person));
        face.put("Name", person.fullName());
        face.put("PhotoData", photoData);
        rpcClient.postRpc(connection.host(), session, "AccessFace.list", Map.of(
                "UserID", userId(person),
                "Count", 1
        ));
        rpcClient.postRpc(connection.host(), session, "AccessFace.insertMulti", Map.of(
                "FaceList", List.of(face)
        ));
    }

    private Map<String, Object> userPayload(ProviderPerson person) {
        var user = new LinkedHashMap<String, Object>();
        user.put("UserID", userId(person));
        user.put("Name", person.fullName());
        user.put("UserType", 0);
        user.put("Authority", 2);
        user.put("Valid", Map.of(
                "BeginTime", formatDeviceTime(person.validFrom(), LocalDateTime.now().minusDays(1)),
                "EndTime", formatDeviceTime(person.validUntil(), LocalDateTime.of(2037, 12, 31, 23, 59, 59))
        ));
        return user;
    }

    private String userId(ProviderPerson person) {
        var document = person.document() == null ? "" : person.document().replaceAll("\\D", "");
        return document.isBlank() ? person.personId().toString() : document;
    }

    private String formatDeviceTime(Instant instant, LocalDateTime fallback) {
        var dateTime = instant == null ? fallback : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return DEVICE_TIME.format(dateTime);
    }

    private String firstText(JsonNode node, String... pointers) {
        for (String pointer : pointers) {
            var value = node.at(pointer);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private ProviderSyncResult result(Instant start, ProviderSyncStatus status, String message) {
        return new ProviderSyncResult(status, safe(message), Duration.between(start, Instant.now()));
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
}
