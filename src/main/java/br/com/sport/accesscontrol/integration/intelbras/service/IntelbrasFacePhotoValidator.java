package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.common.UnprocessableEntityException;
import br.com.sport.accesscontrol.guests.FacePhotoProcessor;
import br.com.sport.accesscontrol.guests.FacePhotoValidator;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasIntegrationException;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasRpc2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnExpression("'${app.intelbras.mode:fake}' == 'real' && '${app.intelbras.face.validation.enabled:true}' == 'true'")
public class IntelbrasFacePhotoValidator implements FacePhotoValidator {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasFacePhotoValidator.class);

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasCgiClient cgiClient;
    private final IntelbrasRpc2Client rpc2Client;

    public IntelbrasFacePhotoValidator(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient,
                                       IntelbrasRpc2Client rpc2Client) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
        this.rpc2Client = rpc2Client;
    }

    @Override
    public void validate(FacePhotoProcessor.ProcessedFacePhoto photo, UUID ownerId, String sourceName) {
        var connection = connectionService.onlineConfiguredDevices().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhuma controladora Intelbras online disponível para validar a foto facial."));
        var userId = temporaryUserId(ownerId);
        var validFrom = LocalDateTime.now().minusMinutes(5);
        var validUntil = LocalDateTime.now().plusMinutes(30);
        IntelbrasRpc2Client.Session session = null;
        try {
            log.info("FACE_UPLOAD_VALIDATION_START owner_id={} source={} host={} user_id_masked={} bytes={} width={} height={} endpoint=/RPC2",
                    ownerId, sourceName, IntelbrasHttpSupport.maskHost(connection.host()), mask(userId),
                    photo.savedSizeBytes(), photo.width(), photo.height());
            session = rpc2Client.login(connection.host(), connection.username(), connection.password());
            rpc2Client.upsertUser(session, userId, null, "Face Validator", validFrom, validUntil);
            rpc2Client.sendFace(session, userId, Base64.getEncoder().encodeToString(photo.bytes()));
            var facePresence = rpc2Client.verifyFace(session, userId);
            if (facePresence == IntelbrasRpc2Client.FacePresence.ABSENT) {
                throw new IntelbrasIntegrationException("Intelbras RPC2 did not confirm temporary validator face.");
            }
            log.info("FACE_UPLOAD_VALIDATION_ACCEPTED owner_id={} source={} host={} user_id_masked={} face_presence={}",
                    ownerId, sourceName, IntelbrasHttpSupport.maskHost(connection.host()), mask(userId), facePresence);
        } catch (IntelbrasIntegrationException exception) {
            if (isFaceQualityRejection(exception.getMessage())) {
                throw new UnprocessableEntityException("A controladora não reconheceu o rosto. Olhe para a câmera com o rosto totalmente visível e descoberto (sem mãos, máscara, óculos escuros ou objetos), bem iluminado e de frente.", exception);
            }
            throw new IllegalStateException("Não foi possível validar a foto facial na controladora Intelbras.", exception);
        } finally {
            cleanup(connection.host(), connection.username(), connection.password(), userId, session);
        }
    }

    private String temporaryUserId(UUID ownerId) {
        var digits = (ownerId == null ? UUID.randomUUID() : ownerId).toString().replaceAll("\\D", "");
        if (digits.length() < 9) {
            digits += UUID.randomUUID().toString().replaceAll("\\D", "");
        }
        return "9" + digits.substring(0, 9);
    }

    private boolean isFaceQualityRejection(String message) {
        var value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return value.contains("288686087")
                || value.contains("no face detected")
                || value.contains("288686099")
                || value.contains("face low align score");
    }

    private void cleanup(String host, String username, String password, String userId, IntelbrasRpc2Client.Session session) {
        if (session != null) {
            rpc2Cleanup(session, userId);
        }
        try {
            cgiClient.removeFace(host, username, password, userId);
        } catch (Exception exception) {
            log.debug("FACE_UPLOAD_VALIDATION_FACE_CLEANUP_SKIPPED host={} user_id_masked={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), mask(userId), exception.getMessage());
        }
        try {
            cgiClient.removeAccessUser(host, username, password, userId);
        } catch (Exception exception) {
            log.warn("FACE_UPLOAD_VALIDATION_USER_CLEANUP_FAILED host={} user_id_masked={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), mask(userId), exception.getMessage());
        }
    }

    private void rpc2Cleanup(IntelbrasRpc2Client.Session session, String userId) {
        try {
            rpc2Client.postRpc(session, "AccessFace.removeMulti", Map.of("UserIDList", List.of(userId)));
        } catch (Exception exception) {
            log.debug("FACE_UPLOAD_VALIDATION_RPC2_FACE_CLEANUP_SKIPPED host={} user_id_masked={} reason={}",
                    IntelbrasHttpSupport.maskHost(session.host()), mask(userId), exception.getMessage());
        }
        try {
            rpc2Client.postRpc(session, "AccessUser.removeMulti", Map.of("UserIDList", List.of(userId)));
        } catch (Exception exception) {
            log.debug("FACE_UPLOAD_VALIDATION_RPC2_USER_CLEANUP_SKIPPED host={} user_id_masked={} reason={}",
                    IntelbrasHttpSupport.maskHost(session.host()), mask(userId), exception.getMessage());
        }
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var raw = value.replaceAll("\\D", "");
        if (raw.isBlank()) {
            raw = value.trim();
        }
        if (raw.length() <= 4) {
            return "****";
        }
        return raw.substring(0, 2) + "****" + raw.substring(raw.length() - 2);
    }
}
