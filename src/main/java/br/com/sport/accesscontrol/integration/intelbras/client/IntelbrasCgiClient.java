package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
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
        var lookup = accessUserLookup(host, username, password, userId);
        if (lookup.found() && (lookup.recNo() == null || lookup.recNo().isBlank())) {
            log.info("intelbras_cgi_access_user_upsert_skip_existing_without_recno host={} user_id={}",
                    IntelbrasHttpSupport.maskHost(host), userId);
            return "EXISTS_WITHOUT_RECNO";
        }
        var action = lookup.found() ? "update" : "insert";
        log.info("intelbras_cgi_access_user_upsert host={} user_id={} action={} recno={}",
                IntelbrasHttpSupport.maskHost(host), userId, action, lookup.recNo() == null ? "" : lookup.recNo());
        var payloads = compatibleAccessUserPayloads(action, lookup.recNo(), userId, cardNo, cardName, validFrom, validUntil);
        try {
            return executeAccessUserPayloads(host, username, password, payloads);
        } catch (IntelbrasIntegrationException exception) {
            if (!"insert".equals(action)) {
                throw exception;
            }
            log.warn("intelbras_device_rejected_payload host={} endpoint=recordUpdater.cgi?action=insert payload={} error={} fallback=update",
                    IntelbrasHttpSupport.maskHost(host), payloads.getFirst().params(), safe(exception.getMessage()));
            var retryLookup = accessUserLookup(host, username, password, userId);
            if (retryLookup.recNo() == null || retryLookup.recNo().isBlank()) {
                throw exception;
            }
            var updatePayloads = compatibleAccessUserPayloads("update", retryLookup.recNo(), userId, cardNo, cardName,
                    validFrom, validUntil);
            return executeAccessUserPayloads(host, username, password, updatePayloads);
        }
    }

    public String upsertAccessUser(String host, String username, String password, String userId, String cardName,
                                   LocalDateTime validFrom, LocalDateTime validUntil) {
        return upsertAccessUser(host, username, password, userId, userId, cardName, validFrom, validUntil);
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

    public String replaceFace(String host, String username, String password, String userId, String userName, String photoData) {
        try {
            removeFace(host, username, password, userId);
        } catch (Exception exception) {
            log.debug("intelbras_cgi_face_remove_before_add_skipped host={} user_id={} reason={}",
                    IntelbrasHttpSupport.maskHost(host), userId, safe(exception.getMessage()));
        }
        var body = faceBody(userId, userName, photoData);
        log.info("intelbras_facial_payload host={} endpoint=/cgi-bin/FaceInfoManager.cgi?action=add payload={}",
                IntelbrasHttpSupport.maskHost(host), sanitizeBody(body));
        var response = postJson(host, username, password, "/cgi-bin/FaceInfoManager.cgi?action=add", body);
        ensureCgiBodyAccepted(host, "/cgi-bin/FaceInfoManager.cgi?action=add", response);
        return response;
    }

    public String removeFace(String host, String username, String password, String userId) {
        var path = "/cgi-bin/FaceInfoManager.cgi?action=remove&UserID=" + encode(userId);
        var response = getText(host, username, password, path);
        ensureCgiBodyAccepted(host, path, response);
        return response;
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
            throw new IntelbrasIntegrationException("Intelbras CGI credentials are not configured.");
        }
        var uri = IntelbrasHttpSupport.uri(host, pathAndQuery);
        var firstRequest = request(method, uri, body, contentType, null);
        logRequest(host, method, pathAndQuery, false, firstRequest, body);
        var firstResponse = send(firstRequest, host);
        logResponse(host, method, pathAndQuery, false, firstResponse);
        if (firstResponse.statusCode() != 401) {
            ensureSuccess(firstResponse.statusCode(), host, pathAndQuery, firstResponse.body());
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
        return authenticatedResponse;
    }

    private HttpResponse<byte[]> getBinary(String host, String username, String password, String pathAndQuery) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IntelbrasIntegrationException("Intelbras CGI credentials are not configured.");
        }
        var uri = IntelbrasHttpSupport.uri(host, pathAndQuery);
        var firstRequest = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .GET()
                .build();
        logRequest(host, "GET", pathAndQuery, false, firstRequest, null);
        var firstResponse = sendBinary(firstRequest, host);
        log.info("intelbras_cgi_http_response method=GET host={} endpoint={} digest=false status={} response_headers={} response_bytes={}",
                IntelbrasHttpSupport.maskHost(host), pathAndQuery, firstResponse.statusCode(),
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
                IntelbrasHttpSupport.maskHost(host), pathAndQuery, authenticatedResponse.statusCode(),
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
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
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
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
            }
        }
        if (lastInterrupted != null) {
            throw new IntelbrasIntegrationException("Intelbras CGI request was interrupted.", lastInterrupted);
        }
        throw new IntelbrasIntegrationException("Intelbras CGI request failed for host "
                + IntelbrasHttpSupport.maskHost(host) + ".", lastIo);
    }

    private void ensureSuccess(int statusCode, String host, String endpoint, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("intelbras_device_rejected_payload host={} endpoint={} status={} response_body={}",
                    IntelbrasHttpSupport.maskHost(host), endpoint, statusCode, safe(body));
            throw new IntelbrasIntegrationException("Intelbras CGI request failed for host "
                    + IntelbrasHttpSupport.maskHost(host) + " endpoint=" + endpoint + " status=" + statusCode
                    + " body=" + safe(body) + ".");
        }
    }

    private void ensureCgiBodyAccepted(String host, String endpoint, String body) {
        var value = body == null ? "" : body.trim();
        var lower = value.toLowerCase();
        if (lower.contains("error") || lower.contains("failed") || lower.equals("false")) {
            log.warn("intelbras_device_rejected_payload host={} endpoint={} response_body={}",
                    IntelbrasHttpSupport.maskHost(host), endpoint, safe(value));
            throw new IntelbrasIntegrationException("Intelbras CGI command rejected for host "
                    + IntelbrasHttpSupport.maskHost(host) + " endpoint=" + endpoint + " body=" + safe(value) + ".");
        }
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
        var path = "/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=" + encode(userId);
        var body = getText(host, username, password, path);
        var values = IntelbrasRecordFinderParser.parseKeyValues(body);
        var foundCount = longValue(values, "found", "Found");
        var found = foundCount != null && foundCount > 0;
        var records = IntelbrasRecordFinderParser.parseRecords(body);
        for (Map<String, Object> record : records) {
            if (userId.equalsIgnoreCase(text(record, "UserID"))) {
                var recNo = text(record, "RecNo");
                if (recNo != null && !recNo.isBlank()) {
                    return new AccessUserLookup(true, recNo);
                }
                found = true;
            }
        }
        if (!records.isEmpty()) {
            var recNo = text(records.getFirst(), "RecNo");
            if (recNo != null && !recNo.isBlank()) {
                return new AccessUserLookup(true, recNo);
            }
            found = true;
        }
        var topLevelRecNo = firstValue(values, "RecNo", "recno", "RecordNo", "recordNo");
        if (topLevelRecNo != null && !topLevelRecNo.isBlank()) {
            return new AccessUserLookup(true, topLevelRecNo);
        }
        return new AccessUserLookup(found, null);
    }

    private List<AccessUserPayload> compatibleAccessUserPayloads(String action, String recNo, String userId,
                                                                 String cardNo, String cardName,
                                                                 LocalDateTime validFrom,
                                                                 LocalDateTime validUntil) {
        var payloads = new ArrayList<AccessUserPayload>();
        payloads.add(accessUserPayload("documented_card_status", action, recNo, userId, cardNo, cardName));
        payloads.add(legacyAccessUserPayload("legacy_full_get", action, recNo, userId, cardNo, cardName,
                validFrom, validUntil));
        payloads.add(accessUserPayload("minimal_without_card_status", action, recNo, userId, cardNo, cardName,
                false));
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
        params.put("CardNo", cardNo == null || cardNo.isBlank() ? userId : cardNo);
        if (includeCardStatus) {
            params.put("CardStatus", "0");
        }
        params.put("CardName", cardName == null || cardName.isBlank() ? userId : cardName);
        params.put("UserID", userId);
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
            try {
                var response = getText(host, username, password, path);
                ensureCgiBodyAccepted(host, path, response);
                log.info("intelbras_record_updater_success host={} variant={} method=GET endpoint={} response_body={}",
                        IntelbrasHttpSupport.maskHost(host), payload.variant(), path, safe(response));
                return response;
            } catch (IntelbrasIntegrationException exception) {
                lastException = exception;
                log.warn("intelbras_device_rejected_payload host={} endpoint={} variant={} method=GET payload={} error={}",
                        IntelbrasHttpSupport.maskHost(host), path, payload.variant(), payload.params(),
                        safe(exception.getMessage()));
            }

            if (index == 0) {
                logAccessUserPayload(host, payload, "POST");
                var postPath = "/cgi-bin/recordUpdater.cgi";
                var body = query(payload.params());
                try {
                    var response = postForm(host, username, password, postPath, body);
                    ensureCgiBodyAccepted(host, postPath, response);
                    log.info("intelbras_record_updater_success host={} variant={} method=POST endpoint={} response_body={}",
                            IntelbrasHttpSupport.maskHost(host), payload.variant(), postPath, safe(response));
                    return response;
                } catch (IntelbrasIntegrationException exception) {
                    lastException = exception;
                    log.warn("intelbras_device_rejected_payload host={} endpoint={} variant={} method=POST payload={} error={}",
                            IntelbrasHttpSupport.maskHost(host), postPath, payload.variant(), payload.params(),
                            safe(exception.getMessage()));
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
            log.warn("intelbras_payload_invalid reason=card_no_not_numeric card_no={} user_id={} params={}",
                    cardNo, params.get("UserID"), params);
        }
        if ("0".equals(params.get("Doors[0]"))) {
            log.warn("intelbras_payload_invalid reason=door_zero_may_be_rejected user_id={} params={}",
                    params.get("UserID"), params);
        }
        if (params.containsKey("TimeSections[0]")) {
            log.warn("intelbras_payload_invalid reason=time_sections_optional_may_be_rejected user_id={} params={}",
                    params.get("UserID"), params);
        }
        if (params.containsKey("ValidDateStart") || params.containsKey("ValidDateEnd")) {
            log.warn("intelbras_payload_invalid reason=date_fields_optional_may_be_rejected user_id={} params={}",
                    params.get("UserID"), params);
        }
    }

    private String faceBody(String userId, String userName, String photoData) {
        var root = new LinkedHashMap<String, Object>();
        var info = new LinkedHashMap<String, Object>();
        root.put("UserID", userId);
        info.put("UserName", userName == null || userName.isBlank() ? userId : userName);
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
        log.info("intelbras_card_payload host={} endpoint=/cgi-bin/recordUpdater.cgi method={} variant={} payload={}",
                IntelbrasHttpSupport.maskHost(host), method, payload.variant(), payload.params());
    }

    private void logRequest(String host, String method, String endpoint, boolean digest, HttpRequest request, String body) {
        log.info("intelbras_cgi_http_request method={} host={} endpoint={} digest={} request_headers={} request_body={}",
                method, IntelbrasHttpSupport.maskHost(host), endpoint, digest, sanitizeHeaders(request.headers().map()),
                sanitizeBody(body));
        log.info("intelbras_request method={} host={} final_url={} query_params={} digest={} encoding=UTF-8 payload={}",
                method, IntelbrasHttpSupport.maskHost(host), safe(request.uri().toString()),
                parseQueryParams(request.uri().getRawQuery()), digest, sanitizeBody(body));
    }

    private void logResponse(String host, String method, String endpoint, boolean digest, HttpResponse<String> response) {
        log.info("intelbras_cgi_http_response method={} host={} endpoint={} digest={} status={} response_headers={} response_body={}",
                method, IntelbrasHttpSupport.maskHost(host), endpoint, digest, response.statusCode(),
                sanitizeHeaders(response.headers().map()), safe(response.body()));
        log.info("intelbras_response method={} host={} endpoint={} digest={} status={} response_body={}",
                method, IntelbrasHttpSupport.maskHost(host), endpoint, digest, response.statusCode(), safe(response.body()));
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
        return body.replaceAll("\"PhotoData\"\\s*:\\s*\\[\\s*\"([^\"]*)\"",
                "\"PhotoData\":[\"<base64-redacted:length=" + photoLength(body) + ":sha256=" + photoHash(body) + ">\"");
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

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)(password|senha)=([^\\s,;]+)", "$1=***");
    }

    private record AccessUserLookup(boolean found, String recNo) {
    }

    private record AccessUserPayload(String variant, Map<String, String> params) {
    }
}
