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
                return result(start, ProviderSyncStatus.FAILED, noTargetDevicesMessage(person));
            }
        } else if (person.areaId() != null) {
            var selectedConnection = connectionService.selectOnlineConfiguredDevice(person.areaId());
            if (selectedConnection.isEmpty()) {
                return result(start, ProviderSyncStatus.FAILED, noTargetDevicesMessage(person));
            }
            connections = java.util.List.of(selectedConnection.get());
        } else {
            return result(start, ProviderSyncStatus.FAILED, noAllowedAreasMessage(person));
        }

        log.info("manual_sync_target_devices person_type={} person_id={} selected_devices_count={} device_ids=[{}] device_hosts=[{}]",
                person.personType(), person.personId(), connections.size(),
                connections.stream().map(c -> c.device().getId() == null ? "null" : c.device().getId().toString()).collect(Collectors.joining(",")),
                connections.stream().map(c -> IntelbrasHttpSupport.maskHost(c.host())).collect(Collectors.joining(",")));
        log.info("SYNC_SELECTED_DEVICES_COUNT person_type={} person_id={} total_targets={} selected_devices_count={}",
                person.personType(), person.personId(), connections.size(), connections.size());
        log.info("SYNC_SELECTED_DEVICE_LIST person_type={} person_id={} devices={}",
                person.personType(), person.personId(),
                connections.stream().map(this::selectedDeviceLog).toList());
        log.info("intelbras_sync_start person_type={} person_id={} devices_count={}",
                person.personType(), person.personId(), connections.size());

        boolean faceRequired = person.facePhotoUrl() != null && !person.facePhotoUrl().isBlank();
        String photoData = null;
        try {
            photoData = faceEncoder.toJpegBase64(person.facePhotoUrl());
        } catch (IllegalArgumentException exception) {
            if (faceRequired) {
                log.error("FACE_ENCODE_FAILED person_type={} person_id={} face_photo_url={} error={}",
                        person.personType(), person.personId(), person.facePhotoUrl(), safe(exception.getMessage()));
                return result(start, ProviderSyncStatus.FAILED,
                        "Foto facial não pôde ser processada: " + safe(exception.getMessage()),
                        connections.size(), 0, connections.size(), 0);
            }
            log.warn("intelbras_real_face_payload_unavailable_user_only person_type={} person_id={} error={}",
                    person.personType(), person.personId(), safe(exception.getMessage()));
        }

        var errors = new ArrayList<String>();
        var totalTargets = connections.size();
        int confirmed = 0;   // VERIFY confirmou a presença -> sucesso real
        int absent = 0;      // VERIFY rodou e a pessoa NÃO está na controladora -> falha
        int unverified = 0;  // VERIFY não pôde ser executada -> nunca conta como sucesso
        boolean hasFace = photoData != null;

        for (IntelbrasDeviceConnection connection : connections) {
            var requestStart = Instant.now();
            var identity = resolveIdentity(person);
            var deviceId = connection.device().getId();
            var deviceName = connection.device().getName();
            var deviceIp = deviceIp(connection);

            var hasPhysicalCard = !digits(person.cardNo()).isBlank();
            var syncMode = hasPhysicalCard ? (hasFace ? "CARD_AND_FACE" : "CARD_ONLY") : (hasFace ? "FACE_ONLY" : null);
            log.info("SYNC_DEVICE_BEGIN device_id={} device_name={} device_ip={} area={} mode={} person_type={} person_id={} has_card={} has_face={}",
                    deviceId, deviceName, deviceIp, deviceArea(connection), syncMode == null ? "INVALID" : syncMode,
                    person.personType(), person.personId(), hasPhysicalCard, hasFace);

            // ── SEND ────────────────────────────────────────────────────────────────────────
            boolean sendThrew = false;
            String sendError = null;
            try {
                if (syncMode == null) {
                    throw new IllegalStateException("Sem método de autenticação (sem cartão e sem foto facial).");
                }
                var validFrom = localDeviceTime(person.validFrom(), LocalDateTime.now(properties.zoneId()).minusDays(1));
                var validUntil = localDeviceTime(person.validUntil(), LocalDateTime.of(2037, 12, 31, 23, 59, 59));

                if (hasPhysicalCard) {
                    logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi", "sending");
                    var response = cgiClient.upsertAccessUser(connection.host(), connection.username(), connection.password(),
                            identity.userId(), identity.cardNo(), person.fullName(), validFrom, validUntil);
                    logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi", "accepted body=" + summarize(response));
                } else {
                    logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi", "sending(face-only)");
                    var response = cgiClient.upsertFaceOnlyAccessUser(connection.host(), connection.username(), connection.password(),
                            identity.userId(), person.fullName(), validFrom, validUntil);
                    logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi",
                            "accepted action=" + response.action() + " body=" + summarize(response.response()));
                }

                if (hasFace) {
                    logStep(connection, "SEND_FACE", "/cgi-bin/FaceInfoManager.cgi?action=add", "sending");
                    var faceResponse = cgiClient.replaceFace(connection.host(), connection.username(), connection.password(),
                            identity.userId(), photoData);
                    logStep(connection, "SEND_FACE", "/cgi-bin/FaceInfoManager.cgi?action=add",
                            "accepted body=" + summarize(faceResponse));
                }
                if (!hasPhysicalCard) {
                    var clearResult = cgiClient.clearCardNoForUser(connection.host(), connection.username(), connection.password(),
                            identity.userId(), person.fullName(), validFrom, validUntil);
                    log.info("FACE_ONLY_CARD_CLEANUP_RESULT device_id={} ip={} user_id_present={} cleared={}",
                            deviceId, deviceIp, identity.userId() != null && !identity.userId().isBlank(), clearResult.cleared());
                }
            } catch (Exception exception) {
                sendThrew = true;
                sendError = safe(exception.getMessage());
                log.warn("SYNC_DEVICE_SEND_FAILED device_id={} device_name={} device_ip={} exception={} error={}",
                        deviceId, deviceName, deviceIp,
                        exception.getClass().getSimpleName(), sendError);
            }

            // ── VERIFY (obrigatória — única fonte de verdade do sucesso) ──────────────────────
            VerificationOutcome outcome = verifyOnDevice(connection, identity.userId(),
                    hasPhysicalCard ? identity.cardNo() : null, hasFace);

            switch (outcome) {
                case PRESENT -> {
                    confirmed++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", true,
                            Duration.between(requestStart, Instant.now()));
                    if (sendThrew) {
                        log.warn("SYNC_DEVICE_RECONCILED_PRESENT device_id={} device_name={} device_ip={} original_error={} result=reconciled_present",
                                deviceId, deviceName, deviceIp, sendError);
                    }
                    log.info("SYNC_DEVICE_CONFIRMED device_id={} device_name={} device_ip={} reconciled={}",
                            deviceId, deviceName, deviceIp, sendThrew);
                }
                case ABSENT -> {
                    absent++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", false,
                            Duration.between(requestStart, Instant.now()));
                    accessMetricsService.recordControllerCommunicationFailure(connection.device());
                    errors.add("Controladora " + deviceLabel(connection) + ": pessoa não encontrada após o envio.");
                    log.warn("SYNC_DEVICE_VERIFY_MISSING device_id={} device_name={} device_ip={} send_threw={} send_error={} result=verify_missing",
                            deviceId, deviceName, deviceIp, sendThrew, sendError);
                }
                case UNAVAILABLE -> {
                    unverified++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", false,
                            Duration.between(requestStart, Instant.now()));
                    accessMetricsService.recordControllerCommunicationFailure(connection.device());
                    errors.add("Controladora " + deviceLabel(connection) + ": não foi possível verificar a sincronização.");
                    log.warn("SYNC_DEVICE_VERIFY_UNAVAILABLE device_id={} device_name={} device_ip={} send_threw={} send_error={} result=verify_unavailable",
                            deviceId, deviceName, deviceIp, sendThrew, sendError);
                }
            }
        }

        // ── Resumo: successCount = controladoras CONFIRMADAS (não requisições enviadas) ──────
        if (totalTargets == 0) {
            return result(start, ProviderSyncStatus.FAILED, "Falha: 0 de 0 controladoras selecionadas.", 0, 0, 0, 0);
        }
        if (confirmed == totalTargets) {
            return result(start, ProviderSyncStatus.SUCCESS,
                    "Confirmado em " + confirmed + " de " + totalTargets + " controladora(s).",
                    totalTargets, confirmed, 0, 0);
        }
        if (confirmed > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Parcial: confirmado em " + confirmed + " de " + totalTargets + " controladora(s). "
                            + String.join("; ", errors),
                    totalTargets, confirmed, absent, unverified);
        }
        if (absent == 0 && unverified > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Necessita verificação: 0 de " + totalTargets + " controladora(s) confirmada(s). "
                            + String.join("; ", errors),
                    totalTargets, 0, 0, unverified);
        }
        return result(start, ProviderSyncStatus.FAILED,
                "Falha: 0 de " + totalTargets + " controladora(s) confirmada(s). "
                        + (errors.isEmpty() ? "Não foi possível confirmar a sincronização." : String.join("; ", errors)),
                totalTargets, 0, absent, unverified);
    }

    private enum VerificationOutcome { PRESENT, ABSENT, UNAVAILABLE }

    /**
     * Mandatory post-send verification — the single source of truth for "synced". Queries the device
     * for the access user (and the face when a photo was sent). Distinguishes ABSENT (query ran, not
     * found) from UNAVAILABLE (query could not run, e.g. comm error / HTML web page), so a device is
     * only counted as success when the person is genuinely confirmed present.
     */
    private VerificationOutcome verifyOnDevice(IntelbrasDeviceConnection connection, String userId,
                                               String requiredCardNo, boolean faceRequired) {
        try {
            logStep(connection, "VERIFY_USER",
                    "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=<redacted>",
                    "querying");
            boolean userPresent = cgiClient.isAccessUserPresent(connection.host(), connection.username(),
                    connection.password(), userId);
            logStep(connection, "VERIFY_USER",
                    "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=<redacted>",
                    userPresent ? "present" : "absent");
            if (!userPresent) {
                return VerificationOutcome.ABSENT;
            }
            if (requiredCardNo != null && !requiredCardNo.isBlank()) {
                logStep(connection, "VERIFY_CARD",
                        "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=<redacted>",
                        "querying");
                boolean cardPresent = cgiClient.isCardAssociatedWithUser(connection.host(), connection.username(),
                        connection.password(), userId, requiredCardNo);
                logStep(connection, "VERIFY_CARD",
                        "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=<redacted>",
                        cardPresent ? "present" : "absent");
                if (!cardPresent) {
                    return VerificationOutcome.ABSENT;
                }
            }
            if (faceRequired) {
                logStep(connection, "VERIFY_FACE",
                        "/cgi-bin/FaceInfoManager.cgi?action=getInfo&UserID=<redacted>", "querying");
                boolean facePresent = cgiClient.isFacePresent(connection.host(), connection.username(),
                        connection.password(), userId);
                logStep(connection, "VERIFY_FACE",
                        "/cgi-bin/FaceInfoManager.cgi?action=getInfo&UserID=<redacted>",
                        facePresent ? "present" : "absent");
                if (!facePresent) {
                    return VerificationOutcome.ABSENT;
                }
            }
            return VerificationOutcome.PRESENT;
        } catch (Exception exception) {
            log.warn("SYNC_DEVICE_VERIFY_ERROR device_id={} device_name={} device_ip={} error={}",
                    connection.device().getId(), connection.device().getName(), deviceIp(connection),
                    safe(exception.getMessage()));
            return VerificationOutcome.UNAVAILABLE;
        }
    }

    private void logStep(IntelbrasDeviceConnection connection, String step, String endpoint, String result) {
        log.info("SYNC_DEVICE_STEP etapa={} deviceId={} deviceName={} deviceIp={} endpointCgi={} httpStatus={} resultado={}",
                step, connection.device().getId(), connection.device().getName(), deviceIp(connection),
                endpoint, "n/a", result);
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
                log.info("intelbras_identity_strategy strategy={} user_id_present={} card_no_present={} person_type={} person_id={} document_present={} operation=remove",
                        identity.strategy().name().toLowerCase(java.util.Locale.ROOT),
                        identity.userId() != null && !identity.userId().isBlank(),
                        identity.cardNo() != null && !identity.cardNo().isBlank(),
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
        var physicalCardNo = digits(person.cardNo());
        var cardSource = physicalCardNo.isBlank() ? "NONE" : "PERSON_CARD";
        log.info("IDENTITY_RESOLVED person_type={} person_id={} strategy={} document_present={} card_source={}",
                person.personType(), person.personId(), identity.strategy().name(), 
                person.document() != null && !person.document().isBlank(), cardSource);
        return new IntelbrasIdentityCodec.IntelbrasIdentity(identity.strategy(), identity.userId(), physicalCardNo);
    }

    private LocalDateTime localDeviceTime(Instant instant, LocalDateTime fallback) {
        return instant == null ? fallback : LocalDateTime.ofInstant(instant, properties.zoneId());
    }

    private ProviderSyncResult result(Instant start, ProviderSyncStatus status, String message) {
        return result(start, status, message, 0, 0, 0, 0);
    }

    private ProviderSyncResult result(Instant start, ProviderSyncStatus status, String message,
                                      int totalTargets, int successCount, int failedCount, int skippedCount) {
        return new ProviderSyncResult(status, safe(message), Duration.between(start, Instant.now()),
                totalTargets, successCount, failedCount, skippedCount);
    }

    private String noAllowedAreasMessage(ProviderPerson person) {
        return person.personType() == PersonType.GUEST
                ? "Visitante sem áreas permitidas"
                : "Pessoa sem áreas permitidas";
    }

    private String noTargetDevicesMessage(ProviderPerson person) {
        return person.personType() == PersonType.GUEST
                ? "Nenhuma catraca encontrada para o camarote selecionado"
                : "Nenhuma controladora online para as áreas permitidas";
    }

    private String deviceLabel(IntelbrasDeviceConnection connection) {
        var device = connection.device();
        if (device.getName() != null && !device.getName().isBlank()) {
            return device.getName() + " (" + deviceIp(connection) + ")";
        }
        return device.getId() == null ? "desconhecida" : device.getId().toString();
    }

    private Map<String, Object> selectedDeviceLog(IntelbrasDeviceConnection connection) {
        var device = connection.device();
        return Map.of(
                "deviceId", device.getId() == null ? "" : device.getId().toString(),
                "name", nullToBlank(device.getName()),
                "ip", nullToBlank(device.getIpAddress()),
                "area", deviceArea(connection),
                "eligibleForSync", true,
                "reason", "selected"
        );
    }

    private String text(Map<String, Object> record, String key) {
        if (record == null) {
            return "";
        }
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : entry.getValue().toString();
            }
        }
        return "";
    }

    private String deviceIp(IntelbrasDeviceConnection connection) {
        return connection.device().getIpAddress() == null ? connection.host() : connection.device().getIpAddress();
    }

    private String deviceArea(IntelbrasDeviceConnection connection) {
        return connection.device().getArea() == null ? "" : nullToBlank(connection.device().getArea().getName());
    }

    private String summarize(String value) {
        var sanitized = safe(value);
        if (sanitized.length() <= 300) {
            return sanitized;
        }
        return sanitized.substring(0, 300) + "...";
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
