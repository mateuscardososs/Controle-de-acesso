package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class IntelbrasCgiClient {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasCgiClient.class);
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final HttpClient httpClient;
    private final IntelbrasProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public IntelbrasCgiClient(IntelbrasProperties properties, ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.getConnectionTimeout())
                .build(), properties, objectMapper);
    }

    IntelbrasCgiClient(HttpClient httpClient, IntelbrasProperties properties, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String getDeviceType(String host, String username, String password) {
        return valueOrBody(getText(host, username, password, "/cgi-bin/magicBox.cgi?action=getDeviceType"), "type");
    }

    public String getSerialNo(String host, String username, String password) {
        return valueOrBody(getText(host, username, password, "/cgi-bin/magicBox.cgi?action=getSerialNo"), "sn");
    }

    public String getSoftwareVersion(String host, String username, String password) {
        return valueOrBody(getText(host, username, password, "/cgi-bin/magicBox.cgi?action=getSoftwareVersion"), "version");
    }

    public String getCurrentTime(String host, String username, String password) {
        return valueOrBody(getText(host, username, password, "/cgi-bin/global.cgi?action=getCurrentTime"), "time");
    }

    public Map<String, String> getNetworkConfig(String host, String username, String password) {
        return IntelbrasRecordFinderParser.parseKeyValues(
                getText(host, username, password, "/cgi-bin/configManager.cgi?action=getConfig&name=Network")
        );
    }

    public List<Map<String, Object>> findAccessControlCards(String host, String username, String password) {
        return findAccessControlCards(host, username, password, null);
    }

    public List<Map<String, Object>> findAccessControlCards(String host, String username, String password, String userId) {
        var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard";
        if (userId != null && !userId.isBlank()) {
            path += "&condition.UserID=" + encode(userId);
        }
        return IntelbrasRecordFinderParser.parseRecords(
                getText(host, username, password, path)
        );
    }

    public List<Map<String, Object>> findAccessControlEvents(String host, String username, String password) {
        return IntelbrasRecordFinderParser.parseRecords(
                getText(host, username, password, "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCardRec")
        );
    }

    public byte[] snapshot(String host, String username, String password) {
        var response = getBinary(host, username, password, "/cgi-bin/snapshot.cgi?channel=1");
        return response.body();
    }

    public String upsertAccessUser(String host, String username, String password, String userId, String cardNo,
                                   String cardName, LocalDateTime validFrom, LocalDateTime validUntil) {
        var finalCardNo = normalizeCardNoForUpsert(userId, cardNo);
        var physicalCardProvided = !digits(cardNo).isBlank()
                && !(digits(userId).length() == 11 && digits(cardNo).equals(digits(userId)));
        var lookup = accessUserLookup(host, username, password, userId);
        if (lookup.found() && (lookup.recNo() == null || lookup.recNo().isBlank())) {
            log.info("intelbras_cgi_access_user_upsert_skip_existing_without_recno host={} user_id_present={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId));
            return "EXISTS_WITHOUT_RECNO";
        }
        var action = lookup.found() ? "update" : "insert";
        log.info("intelbras_cgi_access_user_upsert host={} user_id_present={} action={} recno={} card_no_masked={} physical_card={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), action,
                lookup.recNo() == null ? "" : lookup.recNo(), maskCardNo(finalCardNo), physicalCardProvided);
        var payloads = compatibleAccessUserPayloads(action, lookup.recNo(), userId, finalCardNo, cardName, validFrom, validUntil);
        try {
            return executeAccessUserPayloads(host, username, password, payloads);
        } catch (IntelbrasIntegrationException exception) {
            if (!"insert".equals(action)) {
                // UPDATE failed — if we used a physical card, log clearly
                if (physicalCardProvided) {
                    log.warn("ACCESS_USER_UPDATE_FAILED_PHYSICAL_CARD host={} user_id_present={} card_no_masked={} error={}",
                            IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(finalCardNo),
                            safe(exception.getMessage()));
                }
                throw exception;
            }
            // INSERT failed — try to look up in case the record was partially created
            log.warn("ACCESS_USER_INSERT_FAILED host={} user_id_present={} card_no_masked={} physical_card={} error={} — retrying as update or fallback",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(finalCardNo),
                    physicalCardProvided, safe(exception.getMessage()));
            // Attempt to resolve CardNo conflict: another UserID may own this CardNo from an old sync
            // (e.g. old CPF[0:10] records still registered in the controller)
            if (tryResolveCardNoConflict(host, username, password, finalCardNo, userId)) {
                log.info("ACCESS_USER_INSERT_RETRY_AFTER_CONFLICT_RESOLUTION host={} user_id_present={} card_no_masked={}",
                        IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(finalCardNo));
                try {
                    return executeAccessUserPayloads(host, username, password, payloads);
                } catch (IntelbrasIntegrationException conflictRetryEx) {
                    log.warn("ACCESS_USER_INSERT_AFTER_CONFLICT_RESOLUTION_FAILED host={} user_id_present={} card_no_masked={} error={}",
                            IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(finalCardNo),
                            safe(conflictRetryEx.getMessage()));
                }
            }
            var retryLookup = accessUserLookup(host, username, password, userId);
            if (retryLookup.recNo() != null && !retryLookup.recNo().isBlank()) {
                log.info("ACCESS_USER_INSERT_RETRY_AS_UPDATE host={} user_id_present={} recno={} card_no_masked={}",
                        IntelbrasHttpSupport.maskHost(host), hasText(userId), retryLookup.recNo(), maskCardNo(finalCardNo));
                var updatePayloads = compatibleAccessUserPayloads("update", retryLookup.recNo(), userId, finalCardNo, cardName,
                        validFrom, validUntil);
                return executeAccessUserPayloads(host, username, password, updatePayloads);
            }
            // Physical card INSERT failed and record not found — try document-derived CardNo as last resort
            if (physicalCardProvided) {
                var derivedCardNo = cardNoForUserIdWithoutPhysicalCard(userId);
                log.warn("ACCESS_USER_INSERT_PHYSICAL_CARD_ALL_VARIANTS_FAILED_FALLBACK host={} user_id_present={} failed_card_masked={} fallback_card_masked={} — physical card rejected by all variants, retrying with document-derived CardNo",
                        IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(finalCardNo),
                        maskCardNo(derivedCardNo));
                var fallbackPayloads = compatibleAccessUserPayloads(action, null, userId, derivedCardNo, cardName,
                        validFrom, validUntil);
                return executeAccessUserPayloads(host, username, password, fallbackPayloads);
            }
            throw exception;
        }
    }

    public String upsertAccessUser(String host, String username, String password, String userId, String cardName,
                                   LocalDateTime validFrom, LocalDateTime validUntil) {
        return upsertAccessUser(host, username, password, userId, "", cardName, validFrom, validUntil);
    }

    public AccessUserUpsertResult upsertFaceOnlyAccessUser(String host, String username, String password, String userId,
                                                           String cardName, LocalDateTime validFrom,
                                                           LocalDateTime validUntil) {
        var derivedCardNo = cardNoForUserIdWithoutPhysicalCard(userId);
        var lookup = accessUserLookup(host, username, password, userId);
        if (lookup.found() && (lookup.recNo() == null || lookup.recNo().isBlank())) {
            log.info("ACCESS_USER_FACE_ONLY_BASE_EXISTS_WITHOUT_RECNO host={} user_id_present={} card_no_current_masked={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(lookup.cardNo()));
            return new AccessUserUpsertResult("EXISTS_WITHOUT_RECNO", "exists", derivedCardNo, false);
        }
        if (lookup.found()) {
            log.info("ACCESS_USER_FACE_ONLY_BASE_UPDATE host={} user_id_present={} recno={} current_card_no_masked={} target_card_no_masked={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo(),
                    maskCardNo(lookup.cardNo()), maskCardNo(derivedCardNo));
            var payloads = compatibleAccessUserPayloads("update", lookup.recNo(), userId, derivedCardNo, cardName,
                    validFrom, validUntil);
            var response = executeAccessUserPayloads(host, username, password, payloads);
            return new AccessUserUpsertResult(response, "update", derivedCardNo, false);
        }

        log.info("ACCESS_USER_FACE_ONLY_BASE_INSERT_PREPARED host={} user_id_present={} card_no_masked={} card_no_is_full_document={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(derivedCardNo), derivedCardNo.equals(digits(userId)));
        var payloads = compatibleFaceOnlyInsertPayloads(userId, derivedCardNo, cardName, validFrom, validUntil);
        try {
            var response = executeAccessUserPayloads(host, username, password, payloads);
            return new AccessUserUpsertResult(response, "insert", derivedCardNo, false);
        } catch (IntelbrasIntegrationException exception) {
            log.warn("ACCESS_USER_FACE_ONLY_INSERT_FAILED_LOOKUP_RETRY host={} user_id_present={} card_no_masked={} error={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(derivedCardNo),
                    safe(exception.getMessage()));
            if (tryResolveCardNoConflict(host, username, password, derivedCardNo, userId)) {
                log.info("ACCESS_USER_FACE_ONLY_INSERT_RETRY_AFTER_CONFLICT_RESOLUTION host={} user_id_present={} card_no_masked={}",
                        IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(derivedCardNo));
                try {
                    var response = executeAccessUserPayloads(host, username, password, payloads);
                    return new AccessUserUpsertResult(response, "insert_after_conflict_resolution", derivedCardNo, false);
                } catch (IntelbrasIntegrationException conflictRetryException) {
                    log.warn("ACCESS_USER_FACE_ONLY_INSERT_AFTER_CONFLICT_RESOLUTION_FAILED host={} user_id_present={} card_no_masked={} error={}",
                            IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(derivedCardNo),
                            safe(conflictRetryException.getMessage()));
                }
            }
            var retryLookup = accessUserLookup(host, username, password, userId);
            if (retryLookup.recNo() == null || retryLookup.recNo().isBlank()) {
                throw exception;
            }
            var updatePayloads = compatibleAccessUserPayloads("update", retryLookup.recNo(), userId, derivedCardNo, cardName,
                    validFrom, validUntil);
            var response = executeAccessUserPayloads(host, username, password, updatePayloads);
            return new AccessUserUpsertResult(response, "update_after_partial_insert", derivedCardNo, false);
        }
    }

    /**
     * Removes the AccessControlCard record for a user by issuing a DELETE (action=remove).
     * The AccessUser and FaceInfo records remain intact — the user keeps face-only access.
     *
     * <p>UPDATE strategies (CardNo="" or VerifyMode=3) are intentionally NOT used here:
     * the SS 5531 firmware silently ignores CardNo="" updates and always returns HTTP 200,
     * so those strategies never actually cleared the card in production.
     */
    public CardClearResult clearCardNoForUser(String host, String username, String password, String userId,
                                              String cardName, LocalDateTime validFrom, LocalDateTime validUntil) {
        // ── Initial state read ──────────────────────────────────────────────────────────────────────
        log.info("FACE_ONLY_CARD_LOOKUP_REQUEST host={} user_id_present={} endpoint=recordFinder&name=AccessControlCard",
                IntelbrasHttpSupport.maskHost(host), hasText(userId));
        var lookup = accessUserLookup(host, username, password, userId);
        var cardNoBefore = lookup.cardNo();
        log.info("FACE_ONLY_CARD_LOOKUP_RESPONSE host={} user_id_present={} found={} recno={} card_no_by_userid_query_masked={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.found(),
                lookup.recNo() == null ? "" : lookup.recNo(), maskCardNo(cardNoBefore));

        if (!lookup.found()) {
            log.info("FACE_ONLY_CARD_CLEANUP_SKIP host={} user_id_present={} reason=user_not_found — already clean",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId));
            return new CardClearResult(true, null, cardNoBefore, "", false);
        }

        if (lookup.recNo() == null || lookup.recNo().isBlank()) {
            log.warn("FACE_ONLY_CARD_CLEANUP_SKIP host={} user_id_present={} reason=no_recno_for_delete card_no_before_masked={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), maskCardNo(cardNoBefore));
            return new CardClearResult(false, null, cardNoBefore, cardNoBefore, true);
        }

        // ── DELETE AccessControlCard record ─────────────────────────────────────────────────────────
        var deletePath = "/cgi-bin/recordUpdater.cgi?action=remove&name=AccessControlCard&recno=" + encode(lookup.recNo());
        log.info("FACE_ONLY_CARD_DELETE_REQUEST host={} user_id_present={} recno={} card_no_before_masked={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo(), maskCardNo(cardNoBefore));
        try {
            var deleteResponse = getText(host, username, password, deletePath);
            ensureCgiBodyAccepted(host, deletePath, deleteResponse);
            log.info("FACE_ONLY_CARD_DELETE_RESPONSE host={} user_id_present={} recno={} status=accepted body={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo(), safe(deleteResponse));
        } catch (IntelbrasIntegrationException deleteEx) {
            log.warn("FACE_ONLY_CARD_DELETE_FAILED host={} user_id_present={} recno={} error={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo(), safe(deleteEx.getMessage()));
            return new CardClearResult(false, lookup.recNo(), cardNoBefore, cardNoBefore, true);
        }

        // ── Verify the record is gone ────────────────────────────────────────────────────────────────
        var afterDelete = accessUserLookup(host, username, password, userId);
        if (!afterDelete.found()) {
            log.info("FACE_ONLY_CARD_DELETE_VERIFIED host={} user_id_present={} recno={} cleared=true — AccessControlCard removed",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo());
            return new CardClearResult(true, lookup.recNo(), cardNoBefore, "", false);
        }
        log.warn("FACE_ONLY_CARD_DELETE_VERIFY_FAILED host={} user_id_present={} recno={} card_no_after_masked={} cleared=false — record still present after DELETE",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), lookup.recNo(), maskCardNo(afterDelete.cardNo()));
        return new CardClearResult(false, lookup.recNo(), cardNoBefore, afterDelete.cardNo(), true);
    }

    /** Queries AccessControlCard by CardNo (reverse lookup) to check if a value is registered as a card. */
    private AccessUserLookup lookupAccessControlCardByCardNo(String host, String username, String password,
                                                              String cardNo) {
        var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=" + encode(cardNo);
        var body = getText(host, username, password, path);
        log.info("CARD_LOOKUP_BY_CARDNO host={} card_no_masked={} body_summary={}",
                IntelbrasHttpSupport.maskHost(host), maskCardNo(cardNo), summarize(safe(body)));
        var records = IntelbrasRecordFinderParser.parseRecords(body);
        if (records.isEmpty()) {
            return new AccessUserLookup(false, null, "");
        }
        var first = records.getFirst();
        return new AccessUserLookup(true, blankToEmpty(text(first, "RecNo")), blankToEmpty(text(first, "CardNo")));
    }

    public String removeAccessUser(String host, String username, String password, String userId) {
        var lookup = accessUserLookup(host, username, password, userId);
        if (lookup.recNo() == null || lookup.recNo().isBlank()) {
            return "SKIPPED";
        }
        var path = "/cgi-bin/recordUpdater.cgi?action=remove&name=AccessControlCard&recno=" + encode(lookup.recNo());
        var response = getText(host, username, password, path);
        ensureCgiBodyAccepted(host, path, response);
        return response;
    }

    public String replaceFace(String host, String username, String password, String userId, String photoData) {
        try {
            removeFace(host, username, password, userId);
        } catch (Exception exception) {
            log.debug("intelbras_cgi_face_remove_before_add_skipped host={} user_id_present={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), safe(exception.getMessage()));
        }
        int estimatedJpegBytes = (int)(photoData.length() * 3L / 4);
        log.info("FACE_SYNC_REQUEST endpoint=/cgi-bin/FaceInfoManager.cgi?action=add method=POST host={} user_id_present={} payload_has_user_id=true payload_has_photo_data=true photo_data_count=1 encoded_base64_length={} estimated_jpeg_bytes={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), photoData.length(), estimatedJpegBytes);
        var body = faceBody(userId, photoData);
        log.info("intelbras_facial_payload host={} endpoint=/cgi-bin/FaceInfoManager.cgi?action=add payload={}",
                IntelbrasHttpSupport.maskHost(host), sanitizeBody(body));
        var response = postJson(host, username, password, "/cgi-bin/FaceInfoManager.cgi?action=add", body);
        var accepted = !looksLikeFaceRejection(response);
        log.info("FACE_SYNC_RESPONSE host={} user_id_present={} endpoint=/cgi-bin/FaceInfoManager.cgi?action=add http_status=200 body={} accepted={}",
                IntelbrasHttpSupport.maskHost(host), hasText(userId), summarize(safe(response)), accepted);
        if (!accepted) {
            log.warn("intelbras_face_rejected_by_device host={} user_id_present={} response={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), summarize(safe(response)));
            throw new IntelbrasIntegrationException("Intelbras FaceInfoManager rejected face for user at "
                    + IntelbrasHttpSupport.maskHost(host) + ". Response: " + summarize(safe(response)));
        }
        ensureCgiBodyAccepted(host, "/cgi-bin/FaceInfoManager.cgi?action=add", response);

        // Post-add verification
        verifyFaceStored(host, username, password, userId);

        return response;
    }

    private boolean looksLikeFaceRejection(String body) {
        if (body == null) return false;
        var value = body.trim();
        var lower = value.toLowerCase();
        // Explicit literal false (the most common rejection response from SS 5531)
        if (lower.equals("false")) return true;
        // JSON: {"result": false}  — checks for JSON value false, not just the word
        if (lower.contains(": false") || lower.contains(":false")) return true;
        // JSON: {"errCode": -N} — only negative errCode values indicate rejection
        if (lower.contains("errcode") && (lower.contains(": -") || lower.contains(":-"))) return true;
        // Note: "error" / "failed" substring checks are intentionally excluded here.
        // They cause false positives when the firmware embeds these words in benign messages.
        // Positive responses ("true" or empty) do NOT contain ": false" or negative errCode.
        return false;
    }

    private void verifyFaceStored(String host, String username, String password, String userId) {
        try {
            var path = "/cgi-bin/FaceInfoManager.cgi?action=getInfo&UserID=" + encode(userId);
            var response = getText(host, username, password, path);
            var lower = (response == null ? "" : response.trim().toLowerCase());
            var found = !lower.isBlank() && !lower.equals("false") && !lower.contains("\"count\":0") && !lower.contains("\"count\": 0");
            log.info("FACE_VERIFY_STORED host={} user_id_present={} endpoint={} response_body={} face_found={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), faceInfoEndpoint(), summarize(safe(response)), found);
            if (!found) {
                log.warn("FACE_NOT_CONFIRMED_AFTER_ADD host={} user_id_present={} — face add returned success but verification found no face. Check controller UI.",
                        IntelbrasHttpSupport.maskHost(host), hasText(userId));
            }
        } catch (Exception verifyException) {
            log.warn("FACE_VERIFY_UNAVAILABLE host={} user_id_present={} error={} — face add accepted but post-add verification not supported by this firmware.",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), safe(verifyException.getMessage()));
        }
    }

    public String removeFace(String host, String username, String password, String userId) {
        var path = "/cgi-bin/FaceInfoManager.cgi?action=remove&UserID=" + encode(userId);
        var response = getText(host, username, password, path);
        ensureCgiBodyAccepted(host, path, response);
        return response;
    }

    /**
     * Strict user verification. Unlike {@link #accessUserExists}, this method lets communication,
     * authentication, HTML-login and CGI parsing failures propagate so the caller can mark the device
     * as "verification unavailable" instead of silently treating it as absent.
     */
    public boolean isAccessUserPresent(String host, String username, String password, String userId) {
        return accessUserLookup(host, username, password, userId).found();
    }

    /**
     * Strict face verification. A 2xx HTML web page is rejected by {@link #getText} before this parser
     * runs, so "true" here means the CGI endpoint responded with a non-empty, non-zero face payload.
     */
    public boolean isFacePresent(String host, String username, String password, String userId) {
        var path = "/cgi-bin/FaceInfoManager.cgi?action=getInfo&UserID=" + encode(userId);
        var response = getText(host, username, password, path);
        var present = facePresentFromBody(response);
        log.info("FACE_VERIFY_RESPONSE host={} endpoint={} present={} response_summary={}",
                IntelbrasHttpSupport.maskHost(host), faceInfoEndpoint(), present, summarize(safe(response)));
        return present;
    }

    /**
     * Strict physical-card verification. Queries by CardNo and confirms the returned registration is
     * tied to the expected UserID. If the firmware omits fields in the CardNo lookup, falls back to the
     * already-supported UserID lookup and compares CardNo there.
     */
    public boolean isCardAssociatedWithUser(String host, String username, String password, String userId, String cardNo) {
        var expectedCardNo = digits(cardNo);
        if (expectedCardNo.isBlank()) {
            return true;
        }
        var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=" + encode(expectedCardNo);
        var body = getText(host, username, password, path);
        var records = IntelbrasRecordFinderParser.parseRecords(body);
        var values = IntelbrasRecordFinderParser.parseKeyValues(body);
        var associated = records.stream().anyMatch(record ->
                expectedCardNo.equals(digits(text(record, "CardNo")))
                        && userId.equalsIgnoreCase(blankToEmpty(text(record, "UserID"))));
        if (!associated && records.isEmpty()) {
            var topLevelCardNo = firstValue(values, "CardNo", "cardNo");
            var topLevelUserId = firstValue(values, "UserID", "userId");
            associated = expectedCardNo.equals(digits(topLevelCardNo))
                    && userId.equalsIgnoreCase(blankToEmpty(topLevelUserId));
        }
        if (!associated) {
            var byUser = accessUserLookup(host, username, password, userId);
            associated = byUser.found() && expectedCardNo.equals(digits(byUser.cardNo()));
        }
        log.info("CARD_VERIFY_RESPONSE host={} endpoint={} present={} records_count={}",
                IntelbrasHttpSupport.maskHost(host), "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=<redacted>",
                associated, records.size());
        return associated;
    }

    /**
     * Best-effort check of whether an AccessControlCard record exists for the user on the controller.
     * Used for post-error reconciliation: a sync step may throw (timeout/ambiguous response) after the
     * controller already created the user, so this confirms the real state. Never throws.
     */
    public boolean accessUserExists(String host, String username, String password, String userId) {
        try {
            return isAccessUserPresent(host, username, password, userId);
        } catch (Exception exception) {
            log.warn("ACCESS_USER_EXISTS_CHECK_FAILED host={} user_id_present={} error={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), safe(exception.getMessage()));
            return false;
        }
    }

    /**
     * Best-effort check of whether a face is registered for the user on the controller.
     * Used for post-error reconciliation (e.g. the face add timed out but actually succeeded).
     * Never throws.
     */
    public boolean faceExists(String host, String username, String password, String userId) {
        try {
            return isFacePresent(host, username, password, userId);
        } catch (Exception exception) {
            log.warn("FACE_EXISTS_CHECK_FAILED host={} user_id_present={} error={}",
                    IntelbrasHttpSupport.maskHost(host), hasText(userId), safe(exception.getMessage()));
            return false;
        }
    }

    private String getText(String host, String username, String password, String pathAndQuery) {
        return exchangeText(host, username, password, "GET", pathAndQuery, null, null).body();
    }

    private String postJson(String host, String username, String password, String pathAndQuery, String body) {
        return exchangeText(host, username, password, "POST", pathAndQuery, body, "application/json").body();
    }

    private String postForm(String host, String username, String password, String pathAndQuery, String body) {
        return exchangeText(host, username, password, "POST", pathAndQuery, body,
                "application/x-www-form-urlencoded; charset=UTF-8").body();
    }

    private HttpResponse<String> exchangeText(String host, String username, String password, String method,
                                              String pathAndQuery, String body, String contentType) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IntelbrasIntegrationException("Credenciais Intelbras não configuradas para este dispositivo.");
        }
        var uri = IntelbrasHttpSupport.uri(host, pathAndQuery);
        var firstRequest = request(method, uri, body, contentType, null);
        logRequest(host, method, pathAndQuery, false, firstRequest, body);
        var firstResponse = send(firstRequest, host);
        logResponse(host, method, pathAndQuery, false, firstResponse);
        if (firstResponse.statusCode() != 401) {
            ensureSuccess(firstResponse.statusCode(), host, pathAndQuery, firstResponse.body());
            rejectIfHtml(host, pathAndQuery, firstResponse);
            return firstResponse;
        }

        var challengeHeader = firstResponse.headers().firstValue("WWW-Authenticate").orElse("");
        var challenge = IntelbrasDigestAuth.parseChallenge(challengeHeader);
        var authorization = IntelbrasDigestAuth.authorizationHeader(method, uri, username, password, challenge);
        var authenticatedRequest = request(method, uri, body, contentType, authorization);
        logRequest(host, method, pathAndQuery, true, authenticatedRequest, body);
        var authenticatedResponse = send(authenticatedRequest, host);
        logResponse(host, method, pathAndQuery, true, authenticatedResponse);
        ensureSuccess(authenticatedResponse.statusCode(), host, pathAndQuery, authenticatedResponse.body());
        rejectIfHtml(host, pathAndQuery, authenticatedResponse);
        return authenticatedResponse;
    }

    /**
     * A CGI endpoint must return plain text / key-value / XML — never HTML. If the device replies with
     * its web interface or a login page (HTTP 200 + HTML), it means the CGI endpoint was not reached or
     * authentication did not complete. Treat that as a hard failure so it is never counted as success.
     */
    private void rejectIfHtml(String host, String endpoint, HttpResponse<String> response) {
        var contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
        var body = response.body() == null ? "" : response.body().stripLeading();
        var head = body.substring(0, Math.min(body.length(), 512)).toLowerCase(java.util.Locale.ROOT);
        boolean looksHtml = contentType.contains("text/html")
                || head.startsWith("<!doctype")
                || head.startsWith("<html")
                || head.contains("<head")
                || head.contains("<title")
                || head.contains("<body")
                || head.contains("<form")
                || head.contains("<script");
        if (looksHtml) {
            log.warn("intelbras_cgi_html_response host={} endpoint={} content_type={} — interface web/login retornada; tratado como falha de endpoint/autenticação",
                    IntelbrasHttpSupport.maskHost(host), safe(endpoint), contentType);
            throw new IntelbrasIntegrationException("Intelbras endpoint " + safe(endpoint) + " em "
                    + IntelbrasHttpSupport.maskHost(host) + " retornou HTML (interface web/login) — falha de endpoint/autenticação.");
        }
    }

    private HttpResponse<byte[]> getBinary(String host, String username, String password, String pathAndQuery) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IntelbrasIntegrationException("Credenciais Intelbras não configuradas para este dispositivo.");
        }
        var uri = IntelbrasHttpSupport.uri(host, pathAndQuery);
        var firstRequest = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .GET()
                .build();
        logRequest(host, "GET", pathAndQuery, false, firstRequest, null);
        var firstResponse = sendBinary(firstRequest, host);
        log.info("intelbras_cgi_http_response method=GET host={} endpoint={} digest=false status={} response_headers={} response_bytes={}",
                IntelbrasHttpSupport.maskHost(host), safe(pathAndQuery), firstResponse.statusCode(),
                sanitizeHeaders(firstResponse.headers().map()), firstResponse.body().length);
        if (firstResponse.statusCode() != 401) {
            ensureSuccess(firstResponse.statusCode(), host, pathAndQuery, "<binary>");
            return firstResponse;
        }

        var challengeHeader = firstResponse.headers().firstValue("WWW-Authenticate").orElse("");
        var challenge = IntelbrasDigestAuth.parseChallenge(challengeHeader);
        var authorization = IntelbrasDigestAuth.authorizationHeader("GET", uri, username, password, challenge);
        var authenticatedRequest = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .header("Authorization", authorization)
                .GET()
                .build();
        logRequest(host, "GET", pathAndQuery, true, authenticatedRequest, null);
        var authenticatedResponse = sendBinary(authenticatedRequest, host);
        log.info("intelbras_cgi_http_response method=GET host={} endpoint={} digest=true status={} response_headers={} response_bytes={}",
                IntelbrasHttpSupport.maskHost(host), safe(pathAndQuery), authenticatedResponse.statusCode(),
                sanitizeHeaders(authenticatedResponse.headers().map()), authenticatedResponse.body().length);
        ensureSuccess(authenticatedResponse.statusCode(), host, pathAndQuery, "<binary>");
        return authenticatedResponse;
    }

    private HttpRequest request(String method, java.net.URI uri, String body, String contentType, String authorization) {
        var builder = HttpRequest.newBuilder(uri).timeout(properties.getReadTimeout());
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request, String host) {
        IOException lastIo = null;
        InterruptedException lastInterrupted = null;
        var attempts = Math.max(1, properties.getRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
                log.warn("intelbras_cgi_http_request_failed host={} attempt={} max_attempts={} error={}",
                        IntelbrasHttpSupport.maskHost(host), attempt, attempts, safe(exception.getMessage()));
                backoff(attempt, attempts);
            }
        }
        if (lastInterrupted != null) {
            throw new IntelbrasIntegrationException("Intelbras CGI request was interrupted.", lastInterrupted);
        }
        throw new IntelbrasIntegrationException("Intelbras CGI request failed for host "
                + IntelbrasHttpSupport.maskHost(host) + ".", lastIo);
    }

    private HttpResponse<byte[]> sendBinary(HttpRequest request, String host) {
        IOException lastIo = null;
        InterruptedException lastInterrupted = null;
        var attempts = Math.max(1, properties.getRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
                log.warn("intelbras_cgi_http_binary_request_failed host={} attempt={} max_attempts={} error={}",
                        IntelbrasHttpSupport.maskHost(host), attempt, attempts, safe(exception.getMessage()));
                backoff(attempt, attempts);
            }
        }
        if (lastInterrupted != null) {
            throw new IntelbrasIntegrationException("Intelbras CGI request was interrupted.", lastInterrupted);
        }
        throw new IntelbrasIntegrationException("Intelbras CGI request failed for host "
                + IntelbrasHttpSupport.maskHost(host) + ".", lastIo);
    }

    private void backoff(int attempt, int attempts) {
        if (attempt >= attempts || properties.getRetryBackoff().isZero()) {
            return;
        }
        try {
            Thread.sleep(properties.getRetryBackoff().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureSuccess(int statusCode, String host, String endpoint, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("intelbras_device_rejected_payload host={} endpoint={} status={} response_body={}",
                    IntelbrasHttpSupport.maskHost(host), safe(endpoint), statusCode, safe(body));
            throw new IntelbrasIntegrationException("Intelbras CGI request failed for host "
                    + IntelbrasHttpSupport.maskHost(host) + " endpoint=" + safe(endpoint) + " status=" + statusCode
                    + " body=" + safe(body) + ".");
        }
    }

    private void ensureCgiBodyAccepted(String host, String endpoint, String body) {
        if (!isCgiBodyRejection(body)) {
            return;
        }
        log.warn("intelbras_device_rejected_payload host={} endpoint={} response_body={}",
                IntelbrasHttpSupport.maskHost(host), safe(endpoint), safe(body == null ? "" : body.trim()));
        throw new IntelbrasIntegrationException("Intelbras CGI command rejected for host "
                + IntelbrasHttpSupport.maskHost(host) + " endpoint=" + safe(endpoint) + " body="
                + safe(body == null ? "" : body.trim()) + ".");
    }

    /**
     * Strict rejection detector for HTTP-200 CGI bodies. Only treats a body as a rejection when it is an
     * explicit failure token — NOT merely because it <em>contains</em> the substring "error"/"failed".
     *
     * <p>The substring check used previously caused false-negative syncs: the controller created the
     * user and returned 200, but a benign body that happened to contain "error"/"failed" (e.g. a field
     * like {@code "errorCount":0}) was thrown as a rejection, so a person who was in fact registered
     * was marked SYNC_FAILED. An explicit {@code error}/{@code failed}/{@code false} token, a JSON
     * {@code false} value, or a negative {@code errCode} still count as genuine rejections.
     */
    private boolean isCgiBodyRejection(String body) {
        var value = body == null ? "" : body.trim().toLowerCase();
        if (value.isEmpty() || value.equals("true") || value.equals("ok")) {
            return false;
        }
        if (value.equals("false") || value.equals("error") || value.equals("failed")) {
            return true;
        }
        if (value.contains(": false") || value.contains(":false")) {
            return true;
        }
        return value.contains("errcode") && (value.contains(": -") || value.contains(":-"));
    }

    private String valueOrBody(String body, String preferredKey) {
        var values = IntelbrasRecordFinderParser.parseKeyValues(body);
        if (values.containsKey(preferredKey)) {
            return values.get(preferredKey);
        }
        if (values.size() == 1) {
            return values.values().iterator().next();
        }
        return body == null ? "" : body.trim();
    }

    private AccessUserLookup accessUserLookup(String host, String username, String password, String userId) {
        // Registration record lookup — name=AccessControlCard (NOT AccessControlCardRec, which is the event log)
        var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=" + encode(userId);
        var body = getText(host, username, password, path);
        var values = IntelbrasRecordFinderParser.parseKeyValues(body);
        var foundCount = longValue(values, "found", "Found");
        var found = foundCount != null && foundCount > 0;
        var records = IntelbrasRecordFinderParser.parseRecords(body);
        log.info("ACCESS_USER_LOOKUP_RESPONSE host={} endpoint={} found_hint={} records_count={}",
                IntelbrasHttpSupport.maskHost(host), accessUserLookupEndpoint(), found, records.size());
        for (Map<String, Object> record : records) {
            if (userId.equalsIgnoreCase(text(record, "UserID"))) {
                var recNo = text(record, "RecNo");
                if (recNo != null && !recNo.isBlank()) {
                    return new AccessUserLookup(true, recNo, blankToEmpty(text(record, "CardNo")));
                }
                found = true;
            }
        }
        if (!records.isEmpty()) {
            var recNo = text(records.getFirst(), "RecNo");
            if (recNo != null && !recNo.isBlank()) {
                return new AccessUserLookup(true, recNo, blankToEmpty(text(records.getFirst(), "CardNo")));
            }
            found = true;
        }
        var topLevelRecNo = firstValue(values, "RecNo", "recno", "RecordNo", "recordNo");
        if (topLevelRecNo != null && !topLevelRecNo.isBlank()) {
            return new AccessUserLookup(true, topLevelRecNo, blankToEmpty(firstValue(values, "CardNo", "cardNo")));
        }
        return new AccessUserLookup(found, null, "");
    }

    private boolean facePresentFromBody(String body) {
        var value = body == null ? "" : body.trim();
        if (value.isBlank()) {
            return false;
        }
        var lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("false") || lower.equals("null") || lower.equals("{}") || lower.equals("[]")) {
            return false;
        }
        if (lower.contains("\"count\":0") || lower.contains("\"count\": 0")
                || lower.contains("count=0") || lower.contains("found=0")) {
            return false;
        }
        return true;
    }

    private String accessUserLookupEndpoint() {
        return "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=<redacted>";
    }

    private String faceInfoEndpoint() {
        return "/cgi-bin/FaceInfoManager.cgi?action=getInfo&UserID=<redacted>";
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<AccessUserPayload> compatibleAccessUserPayloads(String action, String recNo, String userId,
                                                                 String cardNo, String cardName,
                                                                 LocalDateTime validFrom,
                                                                 LocalDateTime validUntil) {
        var payloads = new ArrayList<AccessUserPayload>();
        // 1) Primary: CardStatus=0 + validity dates (most common)
        payloads.add(accessUserPayload("documented_card_status_with_validity", action, recNo, userId, cardNo, cardName,
                validFrom, validUntil, true));
        // 2) With CardType=0 and UserType=0 — required by some SS 5531/5541 firmware versions
        payloads.add(accessUserPayloadWithCardType("with_cardtype_usertype_validity", action, recNo, userId, cardNo,
                cardName, validFrom, validUntil));
        // 3) Minimal no-dates (some firmware rejects ValidDateStart/ValidDateEnd on INSERT)
        payloads.add(accessUserPayload("minimal_cardstatus_no_dates", action, recNo, userId, cardNo, cardName, true));
        // 4) Legacy with Doors/TimeSections (broad compatibility, last resort before bare minimal)
        payloads.add(legacyAccessUserPayload("legacy_full_get", action, recNo, userId, cardNo, cardName,
                validFrom, validUntil));
        // 5) Absolutely minimal — just CardNo, CardName, UserID
        payloads.add(accessUserPayload("minimal_without_card_status", action, recNo, userId, cardNo, cardName,
                false));
        return payloads;
    }

    private AccessUserPayload accessUserPayloadWithCardType(String variant, String action, String recNo,
                                                            String userId, String cardNo, String cardName,
                                                            LocalDateTime validFrom, LocalDateTime validUntil) {
        var params = new LinkedHashMap<>(
                accessUserPayload(variant, action, recNo, userId, cardNo, cardName, true).params());
        params.put("CardType", "0");
        params.put("UserType", "0");
        params.put("ValidDateStart", DEVICE_TIME.format(validFrom));
        params.put("ValidDateEnd", DEVICE_TIME.format(validUntil));
        return new AccessUserPayload(variant, params);
    }

    private List<AccessUserPayload> compatibleFaceOnlyInsertPayloads(String userId, String cardNo,
                                                                     String cardName, LocalDateTime validFrom,
                                                                     LocalDateTime validUntil) {
        var payloads = new ArrayList<AccessUserPayload>();
        payloads.add(accessUserPayload("face_only_minimal_document_card", "insert", null, userId, cardNo,
                cardName, false));
        payloads.add(accessUserPayload("face_only_card_status_card_type", "insert", null, userId, cardNo,
                cardName, false));
        payloads.getLast().params().put("CardStatus", "0");
        payloads.getLast().params().put("CardType", "0");
        payloads.add(accessUserPayload("face_only_without_validity", "insert", null, userId, cardNo,
                cardName));
        payloads.add(accessUserPayload("face_only_with_validity", "insert", null, userId, cardNo,
                cardName, validFrom, validUntil, true));
        return payloads;
    }

    private AccessUserPayload accessUserPayload(String variant, String action, String recNo, String userId,
                                                String cardNo, String cardName) {
        return accessUserPayload(variant, action, recNo, userId, cardNo, cardName, true);
    }

    private AccessUserPayload accessUserPayload(String variant, String action, String recNo, String userId,
                                                String cardNo, String cardName, boolean includeCardStatus) {
        var params = new LinkedHashMap<String, String>();
        params.put("action", action);
        params.put("name", "AccessControlCard");
        if (recNo != null && !recNo.isBlank()) {
            params.put("recno", recNo);
        }
        // CardNo is either a real physical card or the 10-digit value derived from CPF.
        if (cardNo != null && !cardNo.isBlank()) {
            params.put("CardNo", cardNo);
        }
        if (includeCardStatus) {
            params.put("CardStatus", "0");
        }
        params.put("CardName", cardName == null || cardName.isBlank() ? userId : cardName);
        params.put("UserID", userId);
        return new AccessUserPayload(variant, params);
    }

    private AccessUserPayload accessUserPayload(String variant, String action, String recNo, String userId,
                                                String cardNo, String cardName, LocalDateTime validFrom,
                                                LocalDateTime validUntil, boolean includeCardStatus) {
        var params = new LinkedHashMap<>(accessUserPayload(variant, action, recNo, userId, cardNo, cardName,
                includeCardStatus).params());
        params.put("ValidDateStart", DEVICE_TIME.format(validFrom));
        params.put("ValidDateEnd", DEVICE_TIME.format(validUntil));
        return new AccessUserPayload(variant, params);
    }

    private AccessUserPayload legacyAccessUserPayload(String variant, String action, String recNo, String userId,
                                                      String cardNo, String cardName, LocalDateTime validFrom,
                                                      LocalDateTime validUntil) {
        var params = new LinkedHashMap<>(accessUserPayload(variant, action, recNo, userId, cardNo, cardName).params());
        params.put("CardType", "0");
        params.put("IsValid", "true");
        params.put("Doors[0]", "0");
        params.put("TimeSections[0]", "255");
        params.put("ValidDateStart", DEVICE_TIME.format(validFrom));
        params.put("ValidDateEnd", DEVICE_TIME.format(validUntil));
        return new AccessUserPayload(variant, params);
    }

    private String accessUserPath(AccessUserPayload payload) {
        return "/cgi-bin/recordUpdater.cgi?" + query(payload.params());
    }

    private String executeAccessUserPayloads(String host, String username, String password,
                                             List<AccessUserPayload> payloads) {
        IntelbrasIntegrationException lastException = null;
        for (int index = 0; index < payloads.size(); index++) {
            var payload = payloads.get(index);
            logAccessUserPayload(host, payload, "GET");
            var path = accessUserPath(payload);
            var cardNoSent = payload.params().get("CardNo");
            var userIdSent = payload.params().get("UserID");
            var hasCardNo = cardNoSent != null && !cardNoSent.isBlank();
            var derivedFromDocument = cardNoSent != null
                    && cardNoSent.equals(IntelbrasIdentityCodec.cardNoFromDocument(userIdSent));
            var cardNoSource = hasCardNo ? (derivedFromDocument ? "DOCUMENT_DERIVED" : "PERSON_CARD") : "NONE";
            var actionType = payload.params().get("action");
            log.info("CARD_SYNC_REQUEST variant={} method=GET host={} user_id_present={} card_no_masked={} has_card_no={} card_no_source={} has_card_status={} has_validity={}",
                    payload.variant(), IntelbrasHttpSupport.maskHost(host),
                    userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent), hasCardNo, cardNoSource,
                    payload.params().containsKey("CardStatus"),
                    payload.params().containsKey("ValidDateStart"));
            log.info("ACCESS_USER_PAYLOAD_VARIANT variant={} method=GET action={} host={} user_id_present={} card_no_masked={} has_cardtype={} has_dates={} has_doors={} full_url={}",
                    payload.variant(), actionType, IntelbrasHttpSupport.maskHost(host),
                    userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent),
                    payload.params().containsKey("CardType"),
                    payload.params().containsKey("ValidDateStart"),
                    payload.params().containsKey("Doors[0]"),
                    safe(path));
            if ("insert".equals(actionType)) {
                log.info("ACCESS_USER_INSERT_REQUEST_FULL host={} user_id_present={} card_no_masked={} variant={} full_url={}",
                        IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                        maskCardNo(cardNoSent), payload.variant(), safe(path));
            }
            try {
                var response = getText(host, username, password, path);
                ensureCgiBodyAccepted(host, path, response);
                log.info("ACCESS_USER_PAYLOAD_VARIANT variant={} method=GET status=accepted host={} user_id_present={} body={}",
                        payload.variant(), IntelbrasHttpSupport.maskHost(host),
                        userIdSent != null && !userIdSent.isBlank(), safe(response));
                log.info("CARD_SYNC_RESPONSE variant={} method=GET host={} user_id_present={} card_no_masked={} accepted=true body={}",
                        payload.variant(), IntelbrasHttpSupport.maskHost(host),
                        userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent), safe(response));
                if ("insert".equals(actionType)) {
                    log.info("ACCESS_USER_INSERT_RESPONSE_FULL host={} user_id_present={} card_no_masked={} variant={} http_status=200 body={}",
                            IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                            maskCardNo(cardNoSent), payload.variant(), safe(response));
                    log.info("ACCESS_USER_INSERT_RESPONSE host={} user_id_present={} card_no_masked={} http_status=200 body={}",
                            IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                            maskCardNo(cardNoSent), safe(response));
                }
                log.info("intelbras_record_updater_success host={} variant={} method=GET endpoint={} response_body={}",
                        IntelbrasHttpSupport.maskHost(host), payload.variant(), safe(path), safe(response));
                return response;
            } catch (IntelbrasIntegrationException exception) {
                lastException = exception;
                log.warn("ACCESS_USER_PAYLOAD_VARIANT variant={} method=GET status=REJECTED host={} user_id_present={} card_no_masked={} error={}",
                        payload.variant(), IntelbrasHttpSupport.maskHost(host),
                        userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent),
                        safe(exception.getMessage()));
                log.warn("CARD_SYNC_RESPONSE variant={} method=GET host={} user_id_present={} card_no_masked={} accepted=false error={}",
                        payload.variant(), IntelbrasHttpSupport.maskHost(host),
                        userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent),
                        safe(exception.getMessage()));
                log.warn("intelbras_device_rejected_payload host={} endpoint={} variant={} method=GET payload={} error={}",
                        IntelbrasHttpSupport.maskHost(host), safe(path), payload.variant(), sanitizeParams(payload.params()),
                        safe(exception.getMessage()));
                if ("insert".equals(actionType)) {
                    log.warn("ACCESS_USER_INSERT_RESPONSE_FULL host={} user_id_present={} card_no_masked={} variant={} accepted=false error={}",
                            IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                            maskCardNo(cardNoSent), payload.variant(), safe(exception.getMessage()));
                    log.warn("ACCESS_USER_INSERT_RESPONSE host={} user_id_present={} card_no_masked={} accepted=false error={}",
                            IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                            maskCardNo(cardNoSent), safe(exception.getMessage()));
                }
            }

            if (index == 0) {
                logAccessUserPayload(host, payload, "POST");
                var postPath = "/cgi-bin/recordUpdater.cgi";
                var body = query(payload.params());
                log.info("CARD_SYNC_REQUEST variant={} method=POST host={} user_id_present={} card_no_masked={} has_card_no={} card_no_source={} has_card_status={} has_validity={}",
                        payload.variant(), IntelbrasHttpSupport.maskHost(host),
                        userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent), hasCardNo, cardNoSource,
                        payload.params().containsKey("CardStatus"),
                        payload.params().containsKey("ValidDateStart"));
                if ("insert".equals(actionType)) {
                    log.info("ACCESS_USER_INSERT_REQUEST host={} user_id_present={} card_no_masked={} method=POST endpoint={} body={}",
                            IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                            maskCardNo(cardNoSent), postPath, sanitizeBody(body));
                }
                try {
                    var response = postForm(host, username, password, postPath, body);
                    ensureCgiBodyAccepted(host, postPath, response);
                    log.info("CARD_SYNC_RESPONSE variant={} method=POST host={} user_id_present={} card_no_masked={} accepted=true body={}",
                            payload.variant(), IntelbrasHttpSupport.maskHost(host),
                            userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent), safe(response));
                    if ("insert".equals(actionType)) {
                        log.info("ACCESS_USER_INSERT_RESPONSE host={} user_id_present={} card_no_masked={} http_status=200 body={}",
                                IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                                maskCardNo(cardNoSent), safe(response));
                    }
                    log.info("intelbras_record_updater_success host={} variant={} method=POST endpoint={} response_body={}",
                            IntelbrasHttpSupport.maskHost(host), payload.variant(), postPath, safe(response));
                    return response;
                } catch (IntelbrasIntegrationException exception) {
                    lastException = exception;
                    log.warn("CARD_SYNC_RESPONSE variant={} method=POST host={} user_id_present={} card_no_masked={} accepted=false error={}",
                            payload.variant(), IntelbrasHttpSupport.maskHost(host),
                            userIdSent != null && !userIdSent.isBlank(), maskCardNo(cardNoSent),
                            safe(exception.getMessage()));
                    log.warn("intelbras_device_rejected_payload host={} endpoint={} variant={} method=POST payload={} error={}",
                            IntelbrasHttpSupport.maskHost(host), postPath, payload.variant(), sanitizeParams(payload.params()),
                            safe(exception.getMessage()));
                    if ("insert".equals(actionType)) {
                        log.warn("ACCESS_USER_INSERT_RESPONSE host={} user_id_present={} card_no_masked={} accepted=false error={}",
                                IntelbrasHttpSupport.maskHost(host), userIdSent != null && !userIdSent.isBlank(),
                                maskCardNo(cardNoSent), safe(exception.getMessage()));
                    }
                }
            }
        }
        throw lastException == null
                ? new IntelbrasIntegrationException("Intelbras recordUpdater did not execute any payload variant.")
                : lastException;
    }

    private void validateAccessUserPayload(Map<String, String> params) {
        var cardNo = params.get("CardNo");
        if (cardNo != null && !cardNo.matches("\\d{1,32}")) {
            log.warn("intelbras_payload_invalid reason=card_no_not_numeric card_no_masked={} user_id_present={} params={}",
                    maskCardNo(cardNo), hasText(params.get("UserID")), sanitizeParams(params));
        }
        if ("0".equals(params.get("Doors[0]"))) {
            log.warn("intelbras_payload_invalid reason=door_zero_may_be_rejected user_id_present={} params={}",
                    hasText(params.get("UserID")), sanitizeParams(params));
        }
        if (params.containsKey("TimeSections[0]")) {
            log.warn("intelbras_payload_invalid reason=time_sections_optional_may_be_rejected user_id_present={} params={}",
                    hasText(params.get("UserID")), sanitizeParams(params));
        }
        if (params.containsKey("ValidDateStart") || params.containsKey("ValidDateEnd")) {
            log.warn("intelbras_payload_invalid reason=date_fields_optional_may_be_rejected user_id_present={} params={}",
                    hasText(params.get("UserID")), sanitizeParams(params));
        }
    }

    private String faceBody(String userId, String photoData) {
        var root = new LinkedHashMap<String, Object>();
        var info = new LinkedHashMap<String, Object>();
        root.put("UserID", userId);
        info.put("PhotoData", List.of(photoData));
        root.put("Info", info);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IntelbrasIntegrationException("Could not serialize Intelbras face payload.", exception);
        }
    }

    private String query(Map<String, String> params) {
        var builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String text(Map<String, Object> record, String key) {
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private Long longValue(Map<String, String> values, String... keys) {
        var value = firstValue(values, keys);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstValue(Map<String, String> values, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private void logAccessUserPayload(String host, AccessUserPayload payload, String method) {
        validateAccessUserPayload(payload.params());
        var params = payload.params();
        var cardNo = params.get("CardNo");
        var hasCard = cardNo != null && !cardNo.isBlank();
        log.info("INTELBRAS_ACCESS_USER_PAYLOAD host={} variant={} method={} user_id_present={} includes_card_no={} card_no_masked={} card_name_present={}",
                IntelbrasHttpSupport.maskHost(host), payload.variant(), method,
                params.containsKey("UserID"), hasCard, maskCardNo(cardNo), params.containsKey("CardName"));
        log.info("intelbras_card_payload host={} endpoint=/cgi-bin/recordUpdater.cgi method={} variant={} payload={}",
                IntelbrasHttpSupport.maskHost(host), method, payload.variant(), sanitizeParams(params));
    }

    private String maskCardNo(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) return "null";
        if (cardNo.length() <= 4) return "****";
        return cardNo.substring(0, 2) + "****" + cardNo.substring(cardNo.length() - 2);
    }

    private void logRequest(String host, String method, String endpoint, boolean digest, HttpRequest request, String body) {
        log.info("intelbras_cgi_http_request method={} host={} endpoint={} digest={} request_headers={} request_body={}",
                method, IntelbrasHttpSupport.maskHost(host), safe(endpoint), digest, sanitizeHeaders(request.headers().map()),
                sanitizeBody(body));
        log.info("intelbras_request method={} host={} final_url={} query_params={} digest={} encoding=UTF-8 payload={}",
                method, IntelbrasHttpSupport.maskHost(host), safe(request.uri().toString()),
                sanitizeParams(parseQueryParams(request.uri().getRawQuery())), digest, sanitizeBody(body));
    }

    private void logResponse(String host, String method, String endpoint, boolean digest, HttpResponse<String> response) {
        log.info("intelbras_cgi_http_response method={} host={} endpoint={} digest={} status={} response_headers={} response_body={}",
                method, IntelbrasHttpSupport.maskHost(host), safe(endpoint), digest, response.statusCode(),
                sanitizeHeaders(response.headers().map()), safe(response.body()));
        log.info("intelbras_response method={} host={} endpoint={} digest={} status={} response_body={}",
                method, IntelbrasHttpSupport.maskHost(host), safe(endpoint), digest, response.statusCode(), safe(response.body()));
        log.info("SYNC_DEVICE_RESPONSE ip={} endpoint={} http_status={} body={}",
                host, safe(endpoint), response.statusCode(), summarize(safe(response.body())));
    }

    private Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
        var sanitized = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("Authorization".equalsIgnoreCase(entry.getKey())) {
                sanitized.put(entry.getKey(), List.of("<redacted>"));
            } else {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }

    private String sanitizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        var withoutPhoto = body.replaceAll("\"PhotoData\"\\s*:\\s*\\[\\s*\"([^\"]*)\"",
                "\"PhotoData\":[\"<base64-redacted:length=" + photoLength(body) + ":sha256=" + photoHash(body) + ">\"");
        return safe(withoutPhoto);
    }

    private int photoLength(String body) {
        var marker = "\"PhotoData\"";
        var markerIndex = body.indexOf(marker);
        if (markerIndex < 0) {
            return 0;
        }
        var firstQuote = body.indexOf('"', body.indexOf('[', markerIndex));
        var secondQuote = firstQuote < 0 ? -1 : body.indexOf('"', firstQuote + 1);
        return firstQuote < 0 || secondQuote < 0 ? 0 : secondQuote - firstQuote - 1;
    }

    private String photoHash(String body) {
        var marker = "\"PhotoData\"";
        var markerIndex = body.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        var firstQuote = body.indexOf('"', body.indexOf('[', markerIndex));
        var secondQuote = firstQuote < 0 ? -1 : body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(body.substring(firstQuote + 1, secondQuote).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    private Map<String, String> parseQueryParams(String rawQuery) {
        var params = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            var index = pair.indexOf('=');
            if (index < 0) {
                params.put(pair, "");
            } else {
                params.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }
        return params;
    }

    private Map<String, String> sanitizeParams(Map<String, String> params) {
        var sanitized = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sanitized.put(entry.getKey(), sensitiveField(entry.getKey()) ? "<redacted>" : safe(entry.getValue()));
        }
        return sanitized;
    }

    private boolean sensitiveField(String key) {
        if (key == null) {
            return false;
        }
        var normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("userid")
                || normalized.equals("cardno")
                || normalized.equals("cardname")
                || normalized.equals("photodata")
                || normalized.equals("validdatestart")
                || normalized.equals("validdateend")
                || normalized.equals("condition.userid")
                || normalized.equals("condition.cardno");
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)(password|senha)=([^\\s,;&}]+)", "$1=***")
                .replaceAll("(?i)(UserID|CardNo|CardName|PhotoData|ValidDateStart|ValidDateEnd|condition\\.UserID|condition\\.CardNo)=([^&\\s,;}]*)", "$1=<redacted>")
                .replaceAll("(?i)(records\\[\\d+\\]\\.(?:UserID|CardNo|CardName)=)([^\\r\\n]*)", "$1<redacted>")
                .replaceAll("(?i)(^|[\\r\\n])((?:UserID|CardNo|CardName)=)([^\\r\\n]*)", "$1$2<redacted>")
                .replaceAll("(?i)\"(UserID|CardNo|CardName|PhotoData|ValidDateStart|ValidDateEnd)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"<redacted>\"");
    }

    /**
     * Detects and removes a conflicting AccessControlCard record that uses the same CardNo
     * but belongs to a different UserID (stale record from a previous sync with the old
     * CPF[0:10] strategy). Called as a fallback when INSERT fails with 400.
     *
     * @return true if a conflict was found and the stale record was deleted, false otherwise
     */
    private boolean tryResolveCardNoConflict(String host, String username, String password,
                                              String cardNo, String ownerUserId) {
        if (cardNo == null || cardNo.isBlank()) {
            return false;
        }
        try {
            var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.CardNo=" + encode(cardNo);
            var body = getText(host, username, password, path);
            var records = IntelbrasRecordFinderParser.parseRecords(body);
            for (var record : records) {
                var recNo = text(record, "RecNo");
                var conflictingUserId = text(record, "UserID");
                if (recNo == null || recNo.isBlank()) {
                    continue;
                }
                if (ownerUserId.equalsIgnoreCase(conflictingUserId)) {
                    continue; // same user — not a conflict
                }
                // Different UserID owns this CardNo — delete the stale record
                var deletePath = "/cgi-bin/recordUpdater.cgi?action=remove&name=AccessControlCard&recno=" + encode(recNo);
                var deleteResponse = getText(host, username, password, deletePath);
                ensureCgiBodyAccepted(host, deletePath, deleteResponse);
                log.info("CARDNO_CONFLICT_RESOLVED device_host={} card_no_masked={} old_user_id_present={} new_user_id_present={} recno={}",
                        IntelbrasHttpSupport.maskHost(host), maskCardNo(cardNo), hasText(conflictingUserId),
                        hasText(ownerUserId), recNo);
                return true;
            }
        } catch (Exception ex) {
            log.warn("CARDNO_CONFLICT_CHECK_FAILED host={} card_no_masked={} owner_user_id_present={} error={} — skipping conflict resolution",
                    IntelbrasHttpSupport.maskHost(host), maskCardNo(cardNo), hasText(ownerUserId),
                    safe(ex.getMessage()));
        }
        return false;
    }

    private String normalizeCardNoForUpsert(String userId, String cardNo) {
        var cardNoDigits = digits(cardNo);
        var userIdDigits = digits(userId);
        if (!cardNoDigits.isBlank() && !(userIdDigits.length() == 11 && cardNoDigits.equals(userIdDigits))) {
            // 6-digit CardNo = UUID_DERIVED (shortNumeric); longer = physical card
            var source = cardNoDigits.length() == 6 ? "UUID_DERIVED" : "PHYSICAL_CARD";
            log.info("CARDNO_SOURCE source={} user_id_present={} card_no_masked={} card_no_raw_masked={}",
                    source, hasText(userIdDigits), maskCardNo(cardNoDigits), maskCardNo(cardNo));
            return cardNoDigits;
        }
        var derived = cardNoForUserIdWithoutPhysicalCard(userId);
        log.info("CARDNO_SOURCE source=DOCUMENT_DERIVED_FALLBACK user_id_present={} card_no_masked={} physical_card_raw_masked={}",
                hasText(userIdDigits), maskCardNo(derived), maskCardNo(cardNo));
        return derived;
    }

    private String cardNoForUserIdWithoutPhysicalCard(String userId) {
        var derived = IntelbrasIdentityCodec.cardNoFromDocument(userId);
        if (!derived.isBlank()) {
            log.info("CARDNO_DERIVED_FROM_DOCUMENT user_id_present={} card_no_masked={}",
                    hasText(digits(userId)), maskCardNo(derived));
            return derived;
        }
        var userIdDigits = digits(userId);
        if (!userIdDigits.isBlank() && userIdDigits.length() <= 10) {
            return userIdDigits;
        }
        throw new IllegalArgumentException("Nao foi possivel derivar CardNo: UserID deve ser CPF com 11 digitos"
                + " ou identificador numerico curto.");
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private record AccessUserLookup(boolean found, String recNo, String cardNo) {
    }

    /**
     * Authoritative result of clearing a CardNo, verified by re-reading the AccessControlCard registration.
     * {@code cleared} is true only when a fresh lookup confirms the stored CardNo is empty — never on HTTP 200 alone.
     */
    public record CardClearResult(boolean cleared, String recNo, String cardNoBefore, String cardNoAfter,
                                  boolean userExists) {
    }

    public record AccessUserUpsertResult(String response, String action, String temporaryCardNo,
                                         boolean usedTemporaryCardNo) {
    }

    private record AccessUserPayload(String variant, Map<String, String> params) {
    }
}
