package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.common.UnprocessableEntityException;
import br.com.sport.accesscontrol.guests.FacePhotoProcessor;
import br.com.sport.accesscontrol.guests.FacePhotoValidator;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasCgiClient;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasHttpSupport;
import br.com.sport.accesscontrol.integration.intelbras.client.IntelbrasIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Component
@ConditionalOnExpression("'${app.intelbras.mode:fake}' == 'real' && '${app.intelbras.face.validation.enabled:true}' == 'true'")
public class IntelbrasFacePhotoValidator implements FacePhotoValidator {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasFacePhotoValidator.class);

    private final IntelbrasDeviceConnectionService connectionService;
    private final IntelbrasCgiClient cgiClient;

    public IntelbrasFacePhotoValidator(IntelbrasDeviceConnectionService connectionService, IntelbrasCgiClient cgiClient) {
        this.connectionService = connectionService;
        this.cgiClient = cgiClient;
    }

    @Override
    public void validate(FacePhotoProcessor.ProcessedFacePhoto photo, UUID ownerId, String sourceName) {
        var connection = connectionService.onlineConfiguredDevices().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhuma controladora Intelbras online disponível para validar a foto facial."));
        var userId = temporaryUserId(ownerId);
        var validFrom = LocalDateTime.now().minusMinutes(5);
        var validUntil = LocalDateTime.now().plusMinutes(30);
        try {
            log.info("FACE_UPLOAD_VALIDATION_START owner_id={} source={} host={} user_id={} bytes={} width={} height={}",
                    ownerId, sourceName, IntelbrasHttpSupport.maskHost(connection.host()), userId,
                    photo.savedSizeBytes(), photo.width(), photo.height());
            cgiClient.upsertFaceOnlyAccessUser(connection.host(), connection.username(), connection.password(),
                    userId, "Face Validator", validFrom, validUntil);
            cgiClient.replaceFace(connection.host(), connection.username(), connection.password(),
                    userId, Base64.getEncoder().encodeToString(photo.bytes()));
            log.info("FACE_UPLOAD_VALIDATION_ACCEPTED owner_id={} source={} host={} user_id={}",
                    ownerId, sourceName, IntelbrasHttpSupport.maskHost(connection.host()), userId);
        } catch (IntelbrasIntegrationException exception) {
            if (isFaceQualityRejection(exception.getMessage())) {
                throw new UnprocessableEntityException("A controladora não reconheceu o rosto. Olhe para a câmera com o rosto totalmente visível e descoberto (sem mãos, máscara, óculos escuros ou objetos), bem iluminado e de frente.", exception);
            }
            throw new IllegalStateException("Não foi possível validar a foto facial na controladora Intelbras.", exception);
        } finally {
            cleanup(connection.host(), connection.username(), connection.password(), userId);
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

    private void cleanup(String host, String username, String password, String userId) {
        try {
            cgiClient.removeFace(host, username, password, userId);
        } catch (Exception exception) {
            log.debug("FACE_UPLOAD_VALIDATION_FACE_CLEANUP_SKIPPED host={} user_id={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), userId, exception.getMessage());
        }
        try {
            cgiClient.removeAccessUser(host, username, password, userId);
        } catch (Exception exception) {
            log.warn("FACE_UPLOAD_VALIDATION_USER_CLEANUP_FAILED host={} user_id={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), userId, exception.getMessage());
        }
    }
}
