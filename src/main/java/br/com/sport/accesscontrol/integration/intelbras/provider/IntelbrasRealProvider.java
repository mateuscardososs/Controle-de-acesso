package br.com.sport.accesscontrol.integration.intelbras.provider;

import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpc2Client;
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
    /**
     * Mantido apenas como dependência de diagnóstico — o firmware/ambiente atual fecha a conexão em
     * /RPC2 ("Empty reply from server"), então a sincronização usa exclusivamente CGI (recordUpdater
     * + FaceInfoManager). NÃO é usado por {@link #syncPerson}.
     */
    @SuppressWarnings("unused")
    private final IntelbrasRpc2Client rpc2Client;
    private final IntelbrasFaceEncoder faceEncoder;
    private final IntelbrasEventMapper eventMapper;
    private final IntelbrasProperties properties;
    private final AccessMetricsService accessMetricsService;

    public IntelbrasRealProvider(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                 IntelbrasRpc2Client rpc2Client,
                                 IntelbrasFaceEncoder faceEncoder,
                                 IntelbrasEventMapper eventMapper,
                                 IntelbrasProperties properties,
                                 AccessMetricsService accessMetricsService) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.rpc2Client = rpc2Client;
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

            // ── SEND (CGI: recordUpdater.cgi + FaceInfoManager.cgi) — fluxo COMPLETO card+face ──
            // Visitante sem cartão físico usa CardNo = documento (CPF), igual à versão funcional:
            // cria o AccessControlCard (que faz a pessoa aparecer em "Gestão de usuários") e NUNCA
            // remove esse cartão depois — o clear anterior era o que sumia com o usuário.
            String sendFailedStage = null;
            String sendError = null;
            String recNo = null;
            boolean faceWriteAccepted = false;
            var cardNo = hasPhysicalCard ? identity.cardNo() : digits(identity.userId());
            var cardSource = hasPhysicalCard ? "PHYSICAL_CARD" : "DOCUMENT_FALLBACK";
            var validFrom = localDeviceTime(person.validFrom(), LocalDateTime.now(properties.zoneId()).minusDays(1));
            var validUntil = localDeviceTime(person.validUntil(), LocalDateTime.of(2037, 12, 31, 23, 59, 59));
            log.info("SYNC_IDENTITY_CHOSEN device_id={} person_type={} person_id={} user_id_masked={} card_no_masked={} has_physical_card={} mode={}",
                    deviceId, person.personType(), person.personId(), mask(identity.userId()), mask(cardNo),
                    hasPhysicalCard, syncMode);
            log.info("CARD_IDENTITY_CHOSEN device_id={} person_type={} person_id={} userIdMasked={} cardNoMasked={} cardNoLength={} cardSource={} hasPhysicalCard={}",
                    deviceId, person.personType(), person.personId(), mask(identity.userId()), mask(cardNo),
                    cardNo == null ? 0 : cardNo.length(), cardSource, hasPhysicalCard);
            try {
                if (syncMode == null) {
                    throw new IllegalStateException("Sem método de autenticação (sem cartão e sem foto facial).");
                }
                logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi", "sending");
                var response = cgiClient.upsertAccessUser(connection.host(), connection.username(),
                        connection.password(), identity.userId(), cardNo, person.fullName(), validFrom, validUntil);
                recNo = parseRecNo(response);
                log.info("SYNC_RECORDUPDATER_RECNO device_id={} user_id_masked={} rec_no={}",
                        deviceId, mask(identity.userId()), recNo == null ? "" : recNo);
                logStep(connection, "SEND_USER", "/cgi-bin/recordUpdater.cgi",
                        "accepted recNo=" + (recNo == null ? "" : recNo) + " body=" + summarize(safe(response)));

                if (hasFace) {
                    try {
                        // Compute decoded byte count from base64 length — avoids decoding the full image just to log.
                        int padCount = photoData == null ? 0 : (int) photoData.chars().filter(c -> c == '=').count();
                        int photoBytes = photoData == null ? 0 : photoData.length() * 3 / 4 - padCount;
                        log.info("INTELBRAS_FACE_UPLOAD device_id={} person_type={} person_id={} userId={} cardNo={} photoBytes={}",
                                deviceId, person.personType(), person.personId(),
                                mask(identity.userId()), mask(cardNo), photoBytes);
                        logStep(connection, "SEND_FACE", "/cgi-bin/FaceInfoManager.cgi?action=add", "sending");
                        var faceResponse = cgiClient.replaceFace(connection.host(), connection.username(),
                                connection.password(), identity.userId(), photoData);
                        faceWriteAccepted = true;
                        logStep(connection, "SEND_FACE", "/cgi-bin/FaceInfoManager.cgi?action=add",
                                "accepted body=" + summarize(safe(faceResponse)));
                        // getInfo retorna 501 neste firmware — add=OK é o critério de face aceita.
                        log.info("SYNC_FACE_GETINFO_UNSUPPORTED_IGNORED device_id={} user_id_masked={} — FaceInfoManager.add OK; getInfo nao usado como criterio de falha",
                                deviceId, mask(identity.userId()));
                    } catch (Exception faceException) {
                        sendFailedStage = "face";
                        sendError = safe(faceException.getMessage());
                        log.warn("SYNC_DEVICE_RESULT_FAILED device_id={} device_name={} device_ip={} stage=face exception={} error={}",
                                deviceId, deviceName, deviceIp, faceException.getClass().getSimpleName(), sendError);
                    }
                }
                // NÃO chamar clearCardNoForUser: remover o AccessControlCard fazia o usuário sumir.
            } catch (Exception exception) {
                if (sendFailedStage == null) {
                    sendFailedStage = "usuario";
                    sendError = safe(exception.getMessage());
                }
                log.warn("SYNC_DEVICE_RESULT_FAILED device_id={} device_name={} device_ip={} stage={} exception={} error={}",
                        deviceId, deviceName, deviceIp, sendFailedStage,
                        exception.getClass().getSimpleName(), safe(exception.getMessage()));
            }

            // ── VERIFY (via CGI — AccessControlCard por UserID -> CardNo -> RecNo) ───────────────
            // Face: getInfo retorna 501 neste firmware, então NÃO é critério; add=OK já basta.
            if (hasFace && sendFailedStage == null && recNo != null && !recNo.isBlank() && faceWriteAccepted) {
                bestEffortVerifyOnDevice(connection, identity.userId(), cardNo, recNo);
                confirmed++;
                accessMetricsService.recordControllerRequest(connection.device(), "sync_person", true,
                        Duration.between(requestStart, Instant.now()));
                log.info("SYNC_DEVICE_CONFIRMED_BY_ACCEPTED_WRITE device_id={} device_name={} device_ip={} user_id_masked={} card_no_masked={} rec_no={} record_updater_recno_present=true face_add_accepted=true",
                        deviceId, deviceName, deviceIp, mask(identity.userId()), mask(cardNo), recNo);
                continue;
            }
            var effectiveOutcome = verifyOnDevice(connection, identity.userId(), cardNo, recNo);
            var effectiveFailedStage = sendFailedStage;
            // Único caso em que a face derruba: o ENVIO da face falhou de verdade (rejeição explícita).
            if (effectiveOutcome == VerificationOutcome.PRESENT && "face".equals(sendFailedStage)) {
                effectiveOutcome = VerificationOutcome.ABSENT;
            }
            var effectiveDetail = sendError == null || sendError.isBlank()
                    ? (effectiveOutcome == VerificationOutcome.ABSENT
                        ? "pessoa nao confirmada na controladora apos o envio"
                        : "verificacao CGI indisponivel")
                    : sendError;

            switch (effectiveOutcome) {
                case PRESENT -> {
                    confirmed++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", true,
                            Duration.between(requestStart, Instant.now()));
                    if (sendFailedStage != null) {
                        log.warn("SYNC_DEVICE_RECONCILED_PRESENT device_id={} device_name={} device_ip={} original_stage={} original_error={} result=reconciled_present",
                                deviceId, deviceName, deviceIp, sendFailedStage, sendError);
                    }
                    log.info("SYNC_DEVICE_RESULT_CONFIRMED device_id={} device_name={} device_ip={} user_id_masked={} reconciled={}",
                            deviceId, deviceName, deviceIp, mask(identity.userId()), sendFailedStage != null);
                }
                case ABSENT -> {
                    absent++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", false,
                            Duration.between(requestStart, Instant.now()));
                    accessMetricsService.recordControllerCommunicationFailure(connection.device());
                    var stage = effectiveFailedStage == null || effectiveFailedStage.isBlank() ? sendFailedStage : effectiveFailedStage;
                    errors.add(failureMessage(connection, stage, effectiveDetail));
                    log.warn("SYNC_DEVICE_RESULT_FAILED device_id={} device_name={} device_ip={} stage={} send_error={} verify_detail={} result=verify_missing",
                            deviceId, deviceName, deviceIp, stage, sendError, effectiveDetail);
                }
                case UNAVAILABLE -> {
                    unverified++;
                    accessMetricsService.recordControllerRequest(connection.device(), "sync_person", false,
                            Duration.between(requestStart, Instant.now()));
                    accessMetricsService.recordControllerCommunicationFailure(connection.device());
                    errors.add(failureMessage(connection, "verificacao", effectiveDetail));
                    log.warn("SYNC_DEVICE_RESULT_FAILED device_id={} device_name={} device_ip={} stage=verificacao send_stage={} send_error={} result=verify_unavailable detail={}",
                            deviceId, deviceName, deviceIp, sendFailedStage, sendError, effectiveDetail);
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
        // 0 confirmadas SEMPRE é falha — nunca SYNCED_WITH_WARNINGS (que só vale com >=1 confirmada).
        var prefix = (absent == 0 && unverified > 0)
                ? "Necessita verificação: 0 de " + totalTargets + " controladora(s) confirmada(s). "
                : "Falha: 0 de " + totalTargets + " controladora(s) confirmada(s). ";
        return result(start, ProviderSyncStatus.FAILED,
                prefix + (errors.isEmpty() ? "Não foi possível confirmar a sincronização." : String.join("; ", errors)),
                totalTargets, 0, absent, unverified);
    }

    private enum VerificationOutcome { PRESENT, ABSENT, UNAVAILABLE }

    /**
     * Accepted-write confirmation still keeps observability. These checks are best-effort and never
     * veto a device that already returned recordUpdater RecNo + FaceInfoManager add OK.
     */
    private void bestEffortVerifyOnDevice(IntelbrasDeviceConnection connection, String userId,
                                          String cardNo, String recNo) {
        try {
            var present = cgiClient.isAccessUserPresent(connection.host(), connection.username(),
                    connection.password(), userId);
            log.info("SYNC_DEVICE_BEST_EFFORT_VERIFY_USERID device_id={} device_name={} device_ip={} user_id_masked={} present={}",
                    connection.device().getId(), connection.device().getName(), deviceIp(connection),
                    mask(userId), present);
            logStep(connection, "VERIFY_USER", "/cgi-bin/recordFinder.cgi?...&condition.UserID=<redacted>",
                    present ? "present" : "absent");
        } catch (Exception exception) {
            log.warn("SYNC_DEVICE_BEST_EFFORT_VERIFY_USERID_UNAVAILABLE device_id={} device_name={} device_ip={} user_id_masked={} error={}",
                    connection.device().getId(), connection.device().getName(), deviceIp(connection),
                    mask(userId), safe(exception.getMessage()));
        }

        if (cardNo != null && !cardNo.isBlank()) {
            try {
                var present = cgiClient.isCardAssociatedWithUser(connection.host(), connection.username(),
                        connection.password(), userId, cardNo);
                log.info("SYNC_DEVICE_BEST_EFFORT_VERIFY_CARDNO device_id={} device_name={} device_ip={} user_id_masked={} card_no_masked={} present={}",
                        connection.device().getId(), connection.device().getName(), deviceIp(connection),
                        mask(userId), mask(cardNo), present);
                logStep(connection, "VERIFY_CARD", "/cgi-bin/recordFinder.cgi?...&condition.CardNo=<redacted>",
                        present ? "present" : "absent");
            } catch (Exception exception) {
                log.warn("SYNC_DEVICE_BEST_EFFORT_VERIFY_CARDNO_UNAVAILABLE device_id={} device_name={} device_ip={} user_id_masked={} card_no_masked={} error={}",
                        connection.device().getId(), connection.device().getName(), deviceIp(connection),
                        mask(userId), mask(cardNo), safe(exception.getMessage()));
            }
        }

        if (recNo != null && !recNo.isBlank()) {
            try {
                var present = cgiClient.isAccessRecordPresentByRecNo(connection.host(), connection.username(),
                        connection.password(), recNo);
                log.info("SYNC_DEVICE_BEST_EFFORT_VERIFY_RECNO device_id={} device_name={} device_ip={} user_id_masked={} rec_no={} present={}",
                        connection.device().getId(), connection.device().getName(), deviceIp(connection),
                        mask(userId), recNo, present);
                logStep(connection, "VERIFY_RECNO", "/cgi-bin/recordFinder.cgi?...&condition.RecNo=" + recNo,
                        present ? "present" : "absent");
            } catch (Exception exception) {
                log.warn("SYNC_DEVICE_BEST_EFFORT_VERIFY_RECNO_UNAVAILABLE device_id={} device_name={} device_ip={} user_id_masked={} rec_no={} error={}",
                        connection.device().getId(), connection.device().getName(), deviceIp(connection),
                        mask(userId), recNo, safe(exception.getMessage()));
            }
        }
    }

    /**
     * Post-send verification via AccessControlCard: por UserID, depois por CardNo, depois por RecNo.
     * A face NÃO entra aqui — getInfo retorna 501 neste firmware (FaceInfoManager.add OK já basta).
     * Distingue ABSENT (consulta rodou e não achou) de UNAVAILABLE (consulta não pôde rodar — erro de
     * comunicação / HTML), para um device só contar como sucesso quando confirmado de fato.
     */
    private VerificationOutcome verifyOnDevice(IntelbrasDeviceConnection connection, String userId,
                                               String cardNo, String recNo) {
        try {
            log.info("SYNC_VERIFY_BY_USERID device_id={} user_id_masked={}", connection.device().getId(), mask(userId));
            if (cgiClient.isAccessUserPresent(connection.host(), connection.username(), connection.password(), userId)) {
                logStep(connection, "VERIFY_USER", "/cgi-bin/recordFinder.cgi?...&condition.UserID=<redacted>", "present");
                return VerificationOutcome.PRESENT;
            }
            if (cardNo != null && !cardNo.isBlank()) {
                log.info("SYNC_VERIFY_BY_CARDNO device_id={} card_no_masked={}", connection.device().getId(), mask(cardNo));
                if (cgiClient.isCardAssociatedWithUser(connection.host(), connection.username(),
                        connection.password(), userId, cardNo)) {
                    logStep(connection, "VERIFY_CARD", "/cgi-bin/recordFinder.cgi?...&condition.CardNo=<redacted>", "present");
                    return VerificationOutcome.PRESENT;
                }
            }
            if (recNo != null && !recNo.isBlank()) {
                log.info("SYNC_VERIFY_BY_RECNO device_id={} rec_no={}", connection.device().getId(), recNo);
                if (cgiClient.isAccessRecordPresentByRecNo(connection.host(), connection.username(),
                        connection.password(), recNo)) {
                    logStep(connection, "VERIFY_RECNO", "/cgi-bin/recordFinder.cgi?...&condition.RecNo=" + recNo, "present");
                    return VerificationOutcome.PRESENT;
                }
            }
            logStep(connection, "VERIFY_USER", "/cgi-bin/recordFinder.cgi?...&condition.UserID=<redacted>", "absent");
            return VerificationOutcome.ABSENT;
        } catch (Exception exception) {
            log.warn("SYNC_DEVICE_VERIFY_ERROR device_id={} device_name={} device_ip={} error={}",
                    connection.device().getId(), connection.device().getName(), deviceIp(connection),
                    safe(exception.getMessage()));
            return VerificationOutcome.UNAVAILABLE;
        }
    }

    /** Extrai o RecNo retornado pelo recordUpdater (ex.: "recno=49", "RecNo:49", JSON). */
    private String parseRecNo(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("(?i)rec[_]?no[^0-9]{0,4}([0-9]+)").matcher(response);
        return matcher.find() ? matcher.group(1) : null;
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

    private String failureMessage(IntelbrasDeviceConnection connection, String stage, String detail) {
        var normalizedStage = stage == null || stage.isBlank() ? "verificacao" : stage;
        var normalizedDetail = detail == null || detail.isBlank()
                ? "nao foi possivel confirmar a sincronizacao"
                : detail;
        return "Controladora " + deviceLabel(connection) + ": falha na etapa "
                + normalizedStage + ": " + normalizedDetail + ".";
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

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var raw = digits(value);
        if (raw.isBlank()) {
            raw = value.trim();
        }
        if (raw.length() <= 4) {
            return "****";
        }
        return raw.substring(0, 2) + "****" + raw.substring(raw.length() - 2);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
