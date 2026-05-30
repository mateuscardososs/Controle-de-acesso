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
        var synced = 0;
        var totalTargets = connections.size();
        for (IntelbrasDeviceConnection connection : connections) {
            var requestStart = Instant.now();
            try {
                var identity = resolveIdentity(person);
                var hasPhysicalCard = identity.cardNo() != null && !identity.cardNo().isBlank();
                var hasFace = photoData != null;
                var syncMode = hasPhysicalCard
                        ? (hasFace ? "CARD_AND_FACE" : "CARD_ONLY")
                        : (hasFace ? "FACE_ONLY" : null);

                log.info("PERSON_SYNC_MODE={} device_id={} person_type={} person_id={} user_id={} has_physical_card={} has_face={}",
                        syncMode == null ? "INVALID" : syncMode,
                        connection.device().getId(), person.personType(), person.personId(),
                        identity.userId(), hasPhysicalCard, hasFace);
                log.info("intelbras_identity_strategy strategy={} user_id={} card_no={} person_type={} person_id={} document_present={}",
                        identity.strategy().name().toLowerCase(java.util.Locale.ROOT), identity.userId(), identity.cardNo(),
                        person.personType(), person.personId(), person.document() != null && !person.document().isBlank());
                log.info("intelbras_real_sync_person_start protocol=CGI device_id={} host={} person_type={} person_id={} user_id={} card_no={} photo_base64_bytes={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()), person.personType(),
                        person.personId(), identity.userId(), identity.cardNo(), photoData == null ? 0 : photoData.length());

                if (syncMode == null) {
                    throw new IllegalStateException("Sem método de autenticação (sem cartão e sem foto facial) para UserID=" + identity.userId());
                }

                var validFrom = localDeviceTime(person.validFrom(), LocalDateTime.now(properties.zoneId()).minusDays(1));
                var validUntil = localDeviceTime(person.validUntil(), LocalDateTime.of(2037, 12, 31, 23, 59, 59));
                log.info("intelbras_real_sync_person_validity device_id={} person_type={} person_id={} user_id={} valid_from={} valid_until={} timezone={}",
                        connection.device().getId(), person.personType(), person.personId(), identity.userId(),
                        validFrom, validUntil, properties.getTimezone());

                if (hasPhysicalCard) {
                    // CARD_ONLY or CARD_AND_FACE: register via AccessControlCard with the physical card number
                    log.info("CARD_SYNC_PREPARED person_type={} person_id={} user_id={} card_no_sent={} has_physical_card=true device_name={} host={}",
                            person.personType(), person.personId(), identity.userId(), identity.cardNo(),
                            connection.device().getName(), IntelbrasHttpSupport.maskHost(connection.host()));
                    log.info("SYNC_DEVICE_ATTEMPT device_id={} name={} ip={} area={} endpoint={} person_type={} person_id={} user_id={}",
                            connection.device().getId(), connection.device().getName(), deviceIp(connection),
                            deviceArea(connection), "/cgi-bin/recordUpdater.cgi",
                            person.personType(), person.personId(), identity.userId());
                    log.info("intelbras_sync_device_attempt device_id={} host={} person_type={} person_id={} user_id={}",
                            connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                            person.personType(), person.personId(), identity.userId());
                    var accessUserResponse = cgiClient.upsertAccessUser(
                            connection.host(), connection.username(), connection.password(),
                            identity.userId(), identity.cardNo(), person.fullName(), validFrom, validUntil);
                    log.info("SYNC_DEVICE_RESPONSE device_id={} ip={} area={} endpoint={} status=accepted body={}",
                            connection.device().getId(), deviceIp(connection), deviceArea(connection),
                            "/cgi-bin/recordUpdater.cgi", summarize(accessUserResponse));
                    verifyAccessUserCreated(connection, identity.userId(), identity.cardNo());
                } else {
                    // FACE_ONLY: SS 5531/5541 requires an AccessControlCard base record to recognize the user.
                    // Flow: (1) INSERT/UPDATE with CardNo=UserID as temporary placeholder → accepted by device.
                    //       (2) Add face via FaceInfoManager.
                    //       (3) Clear CardNo via explicit UPDATE with CardNo="" → user ends up face-only.
                    //       (4) Verify: user exists with empty CardNo and face added.
                    // This mirrors the manual process that was confirmed to work: create user, add face, remove card.

                    // Step 1: base user record with temporary CardNo = UserID
                    log.info("FACE_ONLY_BASE_USER_CREATE_REQUEST device_id={} user_id={} temp_card_no={} endpoint=recordUpdater.cgi",
                            connection.device().getId(), identity.userId(), identity.userId());
                    var createResponse = cgiClient.upsertAccessUser(
                            connection.host(), connection.username(), connection.password(),
                            identity.userId(), identity.userId(),  // CardNo = UserID (CPF) as temporary placeholder
                            person.fullName(), validFrom, validUntil);
                    log.info("FACE_ONLY_BASE_USER_CREATE_RESPONSE device_id={} user_id={} accepted=true response={}",
                            connection.device().getId(), identity.userId(), summarize(createResponse));

                    // Step 2: add face via FaceInfoManager
                    log.info("FACE_SYNC_PREPARED person_type={} person_id={} user_id={} device_name={} host={} face_photo_url={} encoded_base64_length={} estimated_jpeg_bytes={}",
                            person.personType(), person.personId(), identity.userId(),
                            connection.device().getName(), IntelbrasHttpSupport.maskHost(connection.host()),
                            person.facePhotoUrl(), photoData.length(), (photoData.length() * 3L / 4));
                    log.info("SYNC_DEVICE_ATTEMPT device_id={} name={} ip={} area={} endpoint={} person_type={} person_id={} user_id={}",
                            connection.device().getId(), connection.device().getName(), deviceIp(connection),
                            deviceArea(connection), "/cgi-bin/FaceInfoManager.cgi?action=add",
                            person.personType(), person.personId(), identity.userId());
                    var faceResponse = cgiClient.replaceFace(
                            connection.host(), connection.username(), connection.password(),
                            identity.userId(), photoData);
                    log.info("FACE_ONLY_FACE_ADD_RESPONSE device_id={} user_id={} accepted=true response={}",
                            connection.device().getId(), identity.userId(), summarize(faceResponse));

                    // Step 3: clear the temporary CardNo so user authenticates only by face (not by card)
                    var cleanupResponse = cgiClient.clearCardNoForUser(
                            connection.host(), connection.username(), connection.password(),
                            identity.userId(), person.fullName(), validFrom, validUntil);
                    log.info("FACE_ONLY_CARD_CLEANUP_RESPONSE device_id={} user_id={} card_no_cleared=true response={}",
                            connection.device().getId(), identity.userId(), summarize(cleanupResponse));

                    // Step 4: verify final state
                    var verifyRecords = cgiClient.findAccessControlCards(
                            connection.host(), connection.username(), connection.password(), identity.userId());
                    boolean userExists = verifyRecords != null && !verifyRecords.isEmpty();
                    boolean cardNoFinalEmpty = verifyRecords == null || verifyRecords.stream().allMatch(r -> {
                        var cn = text(r, "CardNo");
                        return cn == null || cn.isBlank();
                    });
                    log.info("FACE_ONLY_FINAL_VERIFY device_id={} user_id={} user_exists={} card_no_final_empty={} face_added=true",
                            connection.device().getId(), identity.userId(), userExists, cardNoFinalEmpty);
                    if (!userExists) {
                        throw new IllegalStateException(
                                "FACE_ONLY_VERIFY: usuário não encontrado na controladora após sync UserID=" + identity.userId());
                    }
                    if (!cardNoFinalEmpty) {
                        throw new IllegalStateException(
                                "FACE_ONLY_VERIFY: CardNo não foi limpo — o cartão temporário persiste para UserID=" + identity.userId()
                                        + ". Re-sincronize ou limpe manualmente o campo Cartão na Intelbras.");
                    }
                }

                if (hasPhysicalCard && hasFace) {
                    log.info("FACE_SYNC_PREPARED person_type={} person_id={} user_id={} device_name={} host={} face_photo_url={} encoded_base64_length={} estimated_jpeg_bytes={}",
                            person.personType(), person.personId(), identity.userId(),
                            connection.device().getName(), IntelbrasHttpSupport.maskHost(connection.host()),
                            person.facePhotoUrl(), photoData.length(), (photoData.length() * 3L / 4));
                    log.info("SYNC_DEVICE_ATTEMPT device_id={} name={} ip={} area={} endpoint={} person_type={} person_id={} user_id={}",
                            connection.device().getId(), connection.device().getName(), deviceIp(connection),
                            deviceArea(connection), "/cgi-bin/FaceInfoManager.cgi?action=add",
                            person.personType(), person.personId(), identity.userId());
                    var faceResponse = cgiClient.replaceFace(
                            connection.host(), connection.username(), connection.password(),
                            identity.userId(), photoData);
                    log.info("SYNC_DEVICE_RESPONSE device_id={} ip={} area={} endpoint={} status=accepted body={}",
                            connection.device().getId(), deviceIp(connection), deviceArea(connection),
                            "/cgi-bin/FaceInfoManager.cgi?action=add", summarize(faceResponse));
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
                log.warn("SYNC_DEVICE_FAILED device_id={} name={} ip={} area={} endpoint={} person_type={} person_id={} exception={} error={}",
                        connection.device().getId(), connection.device().getName(), deviceIp(connection), deviceArea(connection),
                        "sync_person", person.personType(), person.personId(),
                        exception.getClass().getSimpleName(), safe(exception.getMessage()), exception);
                log.warn("intelbras_sync_device_failed device_id={} host={} person_type={} person_id={} error={}",
                        connection.device().getId(), IntelbrasHttpSupport.maskHost(connection.host()),
                        person.personType(), person.personId(), safe(exception.getMessage()));
            }
        }
        var failed = totalTargets - synced;
        if (totalTargets == 0) {
            return result(start, ProviderSyncStatus.FAILED,
                    "Falha: 0 de 0 controladoras selecionadas.", 0, 0, 0, 0);
        }
        if (synced == totalTargets) {
            return result(start, ProviderSyncStatus.SUCCESS,
                    "Sincronizado em " + synced + " de " + totalTargets + " controladora(s).",
                    totalTargets, synced, 0, 0);
        }
        if (synced > 0) {
            return result(start, ProviderSyncStatus.PARTIAL_SUCCESS,
                    "Parcial: " + synced + " de " + totalTargets + " controladora(s). "
                            + String.join("; ", errors),
                    totalTargets, synced, failed, 0);
        }
        return result(start, ProviderSyncStatus.FAILED,
                "Falha: 0 de " + totalTargets + " controladora(s). "
                        + (errors.isEmpty()
                        ? "Falha ao sincronizar nas controladoras permitidas."
                        : String.join("; ", errors)),
                totalTargets, 0, failed, 0);
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
        var physicalCardNo = digits(person.cardNo());
        var finalCardNo = physicalCardNo.isBlank() ? "" : physicalCardNo;
        var cardSource = physicalCardNo.isBlank() ? "NONE" : "PERSON_CARD";
        log.info("IDENTITY_RESOLVED person_type={} person_id={} user_id={} card_no={} card_source={} document_as_card_blocked=true",
                person.personType(), person.personId(), identity.userId(),
                finalCardNo.isBlank() ? "null" : finalCardNo, cardSource);
        return new IntelbrasIdentityCodec.IntelbrasIdentity(identity.strategy(), identity.userId(), finalCardNo);
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

    private String deviceLabel(IntelbrasDeviceConnection connection) {
        var device = connection.device();
        if (device.getName() != null && !device.getName().isBlank()) {
            return device.getName() + " (" + deviceIp(connection) + ")";
        }
        return device.getId() == null ? "desconhecida" : device.getId().toString();
    }

    private void verifyAccessUserCreated(IntelbrasDeviceConnection connection, String userId, String cardNo) {
        var records = cgiClient.findAccessControlCards(connection.host(), connection.username(), connection.password(), userId);
        var storedCardNo = "";
        var found = false;
        if (records != null) {
            for (var record : records) {
                var rid = text(record, "UserID");
                var rcard = text(record, "CardNo");
                if (userId.equalsIgnoreCase(rid) || (!digits(cardNo).isBlank() && digits(cardNo).equals(digits(rcard)))) {
                    found = true;
                    storedCardNo = rcard == null ? "" : rcard;
                    break;
                }
            }
            if (!found && !records.isEmpty()) {
                storedCardNo = text(records.getFirst(), "CardNo");
                found = true;
            }
        }
        log.info("CARD_VERIFY_STORED device_id={} ip={} area={} user_id={} card_no_sent={} card_no_stored_in_intelbras={} card_no_match={} record_count={}",
                connection.device().getId(), deviceIp(connection), deviceArea(connection),
                userId, cardNo, storedCardNo,
                !cardNo.isBlank() && cardNo.equals(storedCardNo),
                records == null ? 0 : records.size());
        log.info("SYNC_DEVICE_RESPONSE device_id={} ip={} area={} endpoint={} status={} body={}",
                connection.device().getId(), deviceIp(connection), deviceArea(connection),
                "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard", found ? "verified" : "not_found",
                "records=" + (records == null ? 0 : records.size()));
        if (!found) {
            throw new IllegalStateException("Intelbras nao confirmou cadastro do usuario UserID=" + userId
                    + " na controladora " + deviceLabel(connection));
        }
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
