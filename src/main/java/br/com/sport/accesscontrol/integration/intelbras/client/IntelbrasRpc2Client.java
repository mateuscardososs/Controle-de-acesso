package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class IntelbrasRpc2Client {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasRpc2Client.class);
    private static final String RPC_PATH = "/RPC2";
    private static final String RPC_LOGIN_PATH = "/RPC2_Login";
    private static final String WEB_ROOT_PATH = "/";
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern COOKIE_NAME = Pattern.compile("^\\s*([^=;\\s]+)=");

    private final HttpClient httpClient;
    private final IntelbrasProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestIds = new AtomicInteger(1);

    @Autowired
    public IntelbrasRpc2Client(IntelbrasProperties properties, ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.getConnectionTimeout())
                .build(), properties, objectMapper);
    }

    IntelbrasRpc2Client(HttpClient httpClient, IntelbrasProperties properties, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Session login(String host, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IntelbrasIntegrationException("Credenciais Intelbras nao configuradas para este dispositivo.");
        }
        var maskedHost = IntelbrasHttpSupport.maskHost(host);
        var bootstrap = bootstrapWeb(host);
        log.info("RPC2_LOGIN_STARTED ip={} user_configured={}", maskedHost, true);

        // ── ETAPA 1: challenge (global.login com password vazio) ─────────────────────────────
        // O firmware responde HTTP 200 com result:false + error JUNTO com realm/random — isso é
        // esperado e NÃO é falha. Por isso o challenge não pode passar por ensureRpcSuccess.
        RawRpcResponse challenge;
        try {
            challenge = postRaw(host, RPC_LOGIN_PATH, null, bootstrap.cookie(), "global.login", Map.of(
                    "userName", username,
                    "password", "",
                    "clientType", "Web3.0"
            ), false);
        } catch (IntelbrasIntegrationException exception) {
            log.warn("RPC2_LOGIN_FAILED ip={} stage=LOGIN_CHALLENGE http_status=n/a body= error={}",
                    maskedHost, safe(exception.getMessage()));
            throw new IntelbrasIntegrationException("Falha no login RPC2 (etapa LOGIN_CHALLENGE) em " + maskedHost
                    + ": " + safe(exception.getMessage()), exception);
        }
        var realm = text(challenge.body().at("/params/realm"));
        var random = text(challenge.body().at("/params/random"));
        var challengeSession = text(challenge.body().get("session"));
        log.info("RPC2_LOGIN_CHALLENGE_RESPONSE ip={} http_status={} realm_present={} random_present={} session_present={}",
                maskedHost, challenge.httpStatus(), hasText(realm), hasText(random), hasText(challengeSession));
        if (!hasText(realm) || !hasText(random)) {
            log.warn("RPC2_LOGIN_FAILED ip={} stage=LOGIN_CHALLENGE http_status={} reason=missing_realm_or_random body={}",
                    maskedHost, challenge.httpStatus(), summary(challenge.body()));
            throw new IntelbrasIntegrationException("Falha no login RPC2 (etapa LOGIN_CHALLENGE) em " + maskedHost
                    + ": resposta sem realm/random. http_status=" + challenge.httpStatus() + " body=" + summary(challenge.body()));
        }
        var digestPassword = challengePassword(username, realm, password, random);

        // ── ETAPA 2: commit (global.login com senha digest) ──────────────────────────────────
        RawRpcResponse login;
        try {
            login = postRaw(host, RPC_LOGIN_PATH, challengeSession, challenge.cookie(), "global.login", Map.of(
                    "userName", username,
                    "password", digestPassword,
                    "clientType", "Web3.0",
                    "authorityType", "Default"
            ), false);
        } catch (IntelbrasIntegrationException exception) {
            log.warn("RPC2_LOGIN_FAILED ip={} stage=LOGIN_COMMIT http_status=n/a body= error={}",
                    maskedHost, safe(exception.getMessage()));
            throw new IntelbrasIntegrationException("Falha no login RPC2 (etapa LOGIN_COMMIT) em " + maskedHost
                    + ": " + safe(exception.getMessage()), exception);
        }
        var resultNode = login.body().get("result");
        var commitRejected = (resultNode != null && resultNode.isBoolean() && !resultNode.asBoolean())
                || (login.body().has("error") && !login.body().get("error").isNull());
        var sessionId = text(login.body().get("session"));
        if (!hasText(sessionId)) {
            sessionId = challengeSession;
        }
        var finalCookie = hasText(login.cookie()) ? login.cookie() : challenge.cookie();
        if (commitRejected || !hasText(sessionId)) {
            log.warn("RPC2_LOGIN_FAILED ip={} stage=LOGIN_COMMIT http_status={} reason={} session_present={} cookie_present={} body={}",
                    maskedHost, login.httpStatus(), commitRejected ? "credenciais_rejeitadas" : "sem_session",
                    hasText(sessionId), hasText(finalCookie), summary(login.body()));
            throw new IntelbrasIntegrationException("Falha no login RPC2 (etapa LOGIN_COMMIT) em " + maskedHost
                    + ": " + (commitRejected ? "credenciais rejeitadas" : "resposta sem session")
                    + ". http_status=" + login.httpStatus() + " body=" + summary(login.body()));
        }
        log.info("RPC2_LOGIN_SUCCESS ip={} session_present={} cookie_present={}",
                maskedHost, true, hasText(finalCookie));
        return new Session(host, sessionId, finalCookie, username, Instant.now());
    }

    public AccessUserUpsertResult upsertUser(Session session, String userId, String cardNo, String userName,
                                             LocalDateTime validFrom, LocalDateTime validUntil) {
        var lookup = findUser(session, userId);
        var user = accessUserPayload(userId, userName, validFrom, validUntil);
        var attempts = lookup.found() ? updateUserAttempts(user) : insertUserAttempts(user);
        var action = lookup.found() ? "update" : "insert";
        var lastError = "";
        for (var attempt : attempts) {
            try {
                var response = postRpc(session, attempt.method(), attempt.params());
                return new AccessUserUpsertResult(action, attempt.method(), summary(response));
            } catch (IntelbrasIntegrationException exception) {
                lastError = safe(exception.getMessage());
                log.warn("RPC2_ACCESS_USER_METHOD_FAILED ip={} method={} action={} user_id_masked={} error={}",
                        IntelbrasHttpSupport.maskHost(session.host()), attempt.method(), action, mask(userId), lastError);
            }
        }
        if (!lookup.found()) {
            var afterInsertFailure = findUser(session, userId);
            if (afterInsertFailure.found()) {
                return new AccessUserUpsertResult("insert_reconciled", "AccessUser.startFind", "found_after_insert_error");
            }
        }
        throw new IntelbrasIntegrationException("Intelbras RPC2 AccessUser " + action + " failed for host "
                + IntelbrasHttpSupport.maskHost(session.host()) + " user_id=" + mask(userId)
                + ". Last error: " + lastError);
    }

    public Rpc2CallResult sendFace(Session session, String userId, String photoData) {
        var face = new LinkedHashMap<String, Object>();
        face.put("UserID", userId);
        face.put("PhotoData", List.of(photoData == null ? "" : photoData));
        face.put("FaceData", List.of());
        var params = Map.of("FaceList", List.of(face));
        var response = postRpc(session, "AccessFace.insertMulti", params);
        return new Rpc2CallResult("AccessFace.insertMulti", summary(response));
    }

    public Rpc2CallResult sendCard(Session session, String userId, String cardNo) {
        if (!hasText(cardNo)) {
            return new Rpc2CallResult("AccessCard.insertMulti", "skipped_no_card");
        }
        var card = new LinkedHashMap<String, Object>();
        card.put("UserID", userId);
        card.put("CardNo", digits(cardNo));
        card.put("CardType", 0);
        card.put("CardStatus", 0);
        var attempts = List.of(
                new RpcAttempt("AccessCard.insertMulti", Map.of("CardList", List.of(card))),
                new RpcAttempt("AccessCard.updateMulti", Map.of("CardList", List.of(card))),
                new RpcAttempt("AccessCard.insert", Map.of("CardInfo", card)),
                new RpcAttempt("AccessCard.update", Map.of("CardInfo", card))
        );
        var lastError = "";
        for (var attempt : attempts) {
            try {
                var response = postRpc(session, attempt.method(), attempt.params());
                return new Rpc2CallResult(attempt.method(), summary(response));
            } catch (IntelbrasIntegrationException exception) {
                lastError = safe(exception.getMessage());
                log.warn("RPC2_ACCESS_CARD_METHOD_FAILED ip={} method={} user_id_masked={} card_no_masked={} error={}",
                        IntelbrasHttpSupport.maskHost(session.host()), attempt.method(), mask(userId), mask(cardNo), lastError);
            }
        }
        throw new IntelbrasIntegrationException("Intelbras RPC2 AccessCard insert failed for host "
                + IntelbrasHttpSupport.maskHost(session.host()) + " user_id=" + mask(userId)
                + " card_no=" + mask(cardNo) + ". Last error: " + lastError);
    }

    public UserFindResult findUser(Session session, String userId) {
        var start = postRpc(session, "AccessUser.startFind", Map.of(
                "Condition", Map.of("UserID", userId)
        ));
        var records = extractRecords(start);
        if (containsUserId(records, userId)) {
            return new UserFindResult(true, records.size(), summary(start));
        }
        var token = findText(start, "token", "Token", "FindToken", "findToken");
        if (hasText(token)) {
            try {
                var found = doFind(session, token, userId);
                tryStopFind(session, token);
                return found;
            } catch (IntelbrasIntegrationException exception) {
                tryStopFind(session, token);
                throw exception;
            }
        }
        var count = findLong(start, "count", "Count", "total", "Total", "found", "Found");
        if (count != null && count > 0 && records.isEmpty()) {
            return new UserFindResult(true, count.intValue(), summary(start));
        }
        return new UserFindResult(false, records.size(), summary(start));
    }

    public boolean isUserPresent(Session session, String userId) {
        return findUser(session, userId).found();
    }

    public boolean isCardAssociatedWithUser(Session session, String userId, String cardNo) {
        var expected = digits(cardNo);
        var expectedUserId = digits(userId);
        if (expected.isBlank()) {
            return true;
        }
        var response = postRpc(session, "AccessCard.startFind", Map.of(
                "Condition", Map.of(
                        "UserID", expectedUserId,
                        "CardNo", expected
                )
        ));
        var records = extractRecords(response);
        if (records.stream().anyMatch(record -> {
            var rawUserId = blankToEmpty(text(record.get("UserID")));
            var rawCardNo = blankToEmpty(text(record.get("CardNo")));
            log.info("RECORD_FINDER_USERID_RAW rpc2=true host={} user_id_raw_masked={} user_id_length={} expected_user_id_masked={} expected_user_id_length={}",
                    IntelbrasHttpSupport.maskHost(session.host()), mask(rawUserId), rawUserId.length(),
                    mask(expectedUserId), expectedUserId.length());
            log.info("RECORD_FINDER_CARDNO_RAW rpc2=true host={} card_no_raw_masked={} card_no_length={} expected_card_no_masked={} expected_card_no_length={}",
                    IntelbrasHttpSupport.maskHost(session.host()), mask(rawCardNo), rawCardNo.length(),
                    mask(expected), expected.length());
            return expectedUserId.equals(rawUserId) && expected.equals(rawCardNo);
        })) {
            return true;
        }
        var count = findLong(response, "count", "Count", "total", "Total", "found", "Found");
        return count != null && count > 0 && records.isEmpty();
    }

    public FacePresence verifyFace(Session session, String userId) {
        try {
            var response = postRpc(session, "AccessFace.startFind", Map.of(
                    "Condition", Map.of("UserID", userId)
            ));
            var records = extractRecords(response);
            if (containsUserId(records, userId)) {
                return FacePresence.PRESENT;
            }
            var count = findLong(response, "count", "Count", "total", "Total", "found", "Found");
            return count != null && count > 0 && records.isEmpty() ? FacePresence.PRESENT : FacePresence.ABSENT;
        } catch (IntelbrasIntegrationException exception) {
            if (looksUnsupported(exception.getMessage())) {
                log.info("RPC2_FACE_VERIFY_UNSUPPORTED ip={} user_id_masked={} error={}",
                        IntelbrasHttpSupport.maskHost(session.host()), mask(userId), safe(exception.getMessage()));
                return FacePresence.UNSUPPORTED;
            }
            throw exception;
        }
    }

    public JsonNode postRpc(Session session, String method, Object params) {
        return postRaw(session.host(), RPC_PATH, session.sessionId(), session.cookie(), method, params, true).body();
    }

    private UserFindResult doFind(Session session, String token, String userId) {
        var params = Map.of(
                "token", token,
                "offset", 0,
                "count", 32
        );
        var response = postRpc(session, "AccessUser.doFind", params);
        var records = extractRecords(response);
        if (containsUserId(records, userId)) {
            return new UserFindResult(true, records.size(), summary(response));
        }
        var count = findLong(response, "count", "Count", "total", "Total", "found", "Found");
        return new UserFindResult(count != null && count > 0 && records.isEmpty(), records.size(), summary(response));
    }

    private void tryStopFind(Session session, String token) {
        try {
            postRpc(session, "AccessUser.stopFind", Map.of("token", token));
        } catch (Exception exception) {
            log.debug("RPC2_STOP_FIND_SKIPPED ip={} error={}",
                    IntelbrasHttpSupport.maskHost(session.host()), safe(exception.getMessage()));
        }
    }

    /**
     * @param enforceRpcSuccess when {@code false}, the JSON-level {@code result:false}/{@code error}
     *        checks are skipped. The first {@code global.login} (challenge) intentionally returns
     *        {@code result:false} + an {@code error} node alongside realm/random, so the challenge
     *        must NOT be treated as a failure — otherwise login never reaches the commit step.
     */
    private RawRpcResponse postRaw(String host, String endpoint, String sessionId, String cookie, String method,
                                   Object params, boolean enforceRpcSuccess) {
        var uri = IntelbrasHttpSupport.uri(host, endpoint);
        var requestBody = requestBody(sessionId, method, params);
        var builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", IntelbrasHttpSupport.normalizeHost(host))
                .header("Referer", IntelbrasHttpSupport.normalizeHost(host) + "/")
                .header("User-Agent", BROWSER_USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (hasText(cookie)) {
            builder.header("Cookie", cookie);
        }
        var request = builder.build();
        log.info("RPC2_REQUEST ip={} method={} endpoint={} request_id={} cookie_present={} body={}",
                IntelbrasHttpSupport.maskHost(host), method, endpoint, currentRequestId(requestBody),
                hasText(cookie), sanitizeJson(requestBody));
        var response = send(request, host);
        var responseBody = response.body() == null ? "" : response.body();
        log.info("RPC2_RESPONSE ip={} method={} endpoint={} status={} body={}",
                IntelbrasHttpSupport.maskHost(host), method, endpoint, response.statusCode(),
                summarize(sanitizeJson(responseBody)));
        ensureHttpSuccess(response.statusCode(), host, method, responseBody);
        rejectIfHtml(host, method, response);
        var parsed = parseJson(host, method, responseBody);
        if (enforceRpcSuccess) {
            ensureRpcSuccess(host, method, parsed);
        }
        return new RawRpcResponse(parsed, mergeCookies(cookie, cookiesFrom(response)), response.statusCode());
    }

    private WebBootstrap bootstrapWeb(String host) {
        var maskedHost = IntelbrasHttpSupport.maskHost(host);
        log.info("RPC2_BOOTSTRAP_STARTED ip={} endpoint={}", maskedHost, WEB_ROOT_PATH);
        var uri = IntelbrasHttpSupport.uri(host, WEB_ROOT_PATH);
        var request = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET()
                .build();
        try {
            var response = send(request, host);
            var responseBody = response.body() == null ? "" : response.body();
            ensureHttpSuccess(response.statusCode(), host, "WEB_BOOTSTRAP", responseBody);
            var cookie = mergeCookies(null, cookiesFrom(response));
            log.info("RPC2_BOOTSTRAP_SUCCESS ip={} endpoint={} http_status={} cookie_present={}",
                    maskedHost, WEB_ROOT_PATH, response.statusCode(), hasText(cookie));
            return new WebBootstrap(cookie);
        } catch (IntelbrasIntegrationException exception) {
            log.warn("RPC2_LOGIN_FAILED ip={} stage=BOOTSTRAP http_status=n/a body= error={}",
                    maskedHost, safe(exception.getMessage()));
            throw new IntelbrasIntegrationException("Falha no bootstrap web Intelbras em " + maskedHost
                    + ": " + safe(exception.getMessage()), exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String host) {
        IOException lastIo = null;
        InterruptedException lastInterrupted = null;
        var attempts = Math.max(1, properties.getRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
                log.warn("RPC2_REQUEST_IO_FAILED ip={} attempt={} max_attempts={} error={}",
                        IntelbrasHttpSupport.maskHost(host), attempt, attempts, safe(exception.getMessage()));
                backoff(attempt, attempts);
            }
        }
        if (lastInterrupted != null) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 request was interrupted.", lastInterrupted);
        }
        throw new IntelbrasIntegrationException("Intelbras RPC2 request failed for host "
                + IntelbrasHttpSupport.maskHost(host) + ".", lastIo);
    }

    private void ensureHttpSuccess(int statusCode, String host, String method, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 request failed for host "
                    + IntelbrasHttpSupport.maskHost(host) + " method=" + method + " status=" + statusCode
                    + " body=" + summarize(sanitizeJson(body)) + ".");
        }
    }

    private void rejectIfHtml(String host, String method, HttpResponse<String> response) {
        var contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        var body = response.body() == null ? "" : response.body().stripLeading();
        var head = body.substring(0, Math.min(body.length(), 512)).toLowerCase(Locale.ROOT);
        var html = contentType.contains("text/html")
                || head.startsWith("<!doctype")
                || head.startsWith("<html")
                || head.contains("<head")
                || head.contains("<title")
                || head.contains("<body")
                || head.contains("<form")
                || head.contains("<script");
        if (html) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 method " + method + " at "
                    + IntelbrasHttpSupport.maskHost(host) + " returned HTML/login page.");
        }
    }

    private JsonNode parseJson(String host, String method, String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 response was not valid JSON for host "
                    + IntelbrasHttpSupport.maskHost(host) + " method=" + method + ".", exception);
        }
    }

    private void ensureRpcSuccess(String host, String method, JsonNode body) {
        if (body.has("error") && !body.get("error").isNull()) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 internal error for host "
                    + IntelbrasHttpSupport.maskHost(host) + " method=" + method + " body=" + summary(body) + ".");
        }
        var result = body.get("result");
        if (result != null && result.isBoolean() && !result.asBoolean()) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 result=false for host "
                    + IntelbrasHttpSupport.maskHost(host) + " method=" + method + " body=" + summary(body) + ".");
        }
        if (containsFailureNode(body)) {
            throw new IntelbrasIntegrationException("Intelbras RPC2 command rejected for host "
                    + IntelbrasHttpSupport.maskHost(host) + " method=" + method + " body=" + summary(body) + ".");
        }
    }

    private boolean containsFailureNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var key = entry.getKey().toLowerCase(Locale.ROOT);
                var value = entry.getValue();
                if ((key.equals("errcode") || key.equals("errorcode")) && value.canConvertToLong() && value.asLong() < 0) {
                    return true;
                }
                if ((key.equals("errorlist") || key.equals("faillist") || key.equals("failedlist"))
                        && value.isArray() && !value.isEmpty()) {
                    return true;
                }
                if ((key.equals("result") || key.equals("success")) && value.isBoolean() && !value.asBoolean()) {
                    return true;
                }
                if (containsFailureNode(value)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsFailureNode(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ObjectNode requestBodyNode(String sessionId, String method, Object params) {
        var root = objectMapper.createObjectNode();
        root.put("method", method);
        root.set("params", objectMapper.valueToTree(params == null ? Map.of() : params));
        root.put("id", requestIds.getAndIncrement());
        if (hasText(sessionId)) {
            putSession(root, sessionId);
        }
        return root;
    }

    private String requestBody(String sessionId, String method, Object params) {
        try {
            return objectMapper.writeValueAsString(requestBodyNode(sessionId, method, params));
        } catch (IOException exception) {
            throw new IntelbrasIntegrationException("Could not serialize Intelbras RPC2 request.", exception);
        }
    }

    private String currentRequestId(String requestBody) {
        try {
            return text(objectMapper.readTree(requestBody).get("id"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void putSession(ObjectNode root, String sessionId) {
        if (sessionId.chars().allMatch(Character::isDigit)) {
            try {
                root.put("session", Long.parseLong(sessionId));
                return;
            } catch (NumberFormatException ignored) {
                // String sessions are accepted by some firmwares.
            }
        }
        root.put("session", sessionId);
    }

    private Map<String, Object> accessUserPayload(String userId, String userName, LocalDateTime validFrom,
                                                  LocalDateTime validUntil) {
        var user = new LinkedHashMap<String, Object>();
        user.put("UserID", userId);
        user.put("UserName", userName == null ? "" : userName);
        user.put("UserType", 0);
        user.put("Authority", 2);
        user.put("Password", "");
        user.put("Doors", List.of(0));
        user.put("TimeSections", List.of(255));
        user.put("ValidFrom", format(validFrom));
        user.put("ValidTo", format(validUntil));
        return user;
    }

    private List<RpcAttempt> insertUserAttempts(Map<String, Object> user) {
        return List.of(
                new RpcAttempt("AccessUser.insertMulti", Map.of("UserList", List.of(user))),
                new RpcAttempt("AccessUser.insert", Map.of("UserInfo", user)),
                new RpcAttempt("AccessUser.insert", user)
        );
    }

    private List<RpcAttempt> updateUserAttempts(Map<String, Object> user) {
        return List.of(
                new RpcAttempt("AccessUser.updateMulti", Map.of("UserList", List.of(user))),
                new RpcAttempt("AccessUser.update", Map.of("UserInfo", user)),
                new RpcAttempt("AccessUser.update", user)
        );
    }

    private List<Map<String, JsonNode>> extractRecords(JsonNode node) {
        var records = new ArrayList<Map<String, JsonNode>>();
        collectRecordObjects(node, records);
        return records;
    }

    private void collectRecordObjects(JsonNode node, List<Map<String, JsonNode>> records) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject() && node.has("UserID")) {
            var record = new TreeMap<String, JsonNode>(String.CASE_INSENSITIVE_ORDER);
            node.fields().forEachRemaining(entry -> record.put(entry.getKey(), entry.getValue()));
            records.add(record);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectRecordObjects(entry.getValue(), records));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectRecordObjects(child, records));
        }
    }

    private boolean containsUserId(List<Map<String, JsonNode>> records, String userId) {
        return records.stream().anyMatch(record -> userId.equalsIgnoreCase(text(record.get("UserID"))));
    }

    private String findText(JsonNode node, String... keys) {
        for (String key : keys) {
            var found = findNode(node, key);
            if (found != null && !found.isNull()) {
                return found.asText();
            }
        }
        return "";
    }

    private Long findLong(JsonNode node, String... keys) {
        for (String key : keys) {
            var found = findNode(node, key);
            if (found != null && found.canConvertToLong()) {
                return found.asLong();
            }
        }
        return null;
    }

    private JsonNode findNode(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
                var child = findNode(entry.getValue(), key);
                if (child != null) {
                    return child;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                var found = findNode(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<String> cookiesFrom(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(value -> value == null ? "" : value.split(";", 2)[0].trim())
                .filter(this::hasText)
                .toList();
    }

    private String mergeCookies(String existingCookieHeader, List<String> newCookies) {
        var cookies = new LinkedHashMap<String, String>();
        addCookiePairs(cookies, existingCookieHeader);
        for (String cookie : newCookies) {
            addCookiePairs(cookies, cookie);
        }
        return String.join("; ", cookies.values());
    }

    private void addCookiePairs(Map<String, String> cookies, String cookieHeader) {
        if (!hasText(cookieHeader)) {
            return;
        }
        for (String part : cookieHeader.split(";")) {
            var cookie = part.trim();
            if (cookie.isBlank()) {
                continue;
            }
            var matcher = COOKIE_NAME.matcher(cookie);
            if (matcher.find()) {
                cookies.put(matcher.group(1), cookie);
            }
        }
    }

    private String format(LocalDateTime value) {
        return (value == null ? LocalDateTime.of(2037, 12, 31, 23, 59, 59) : value).format(DEVICE_TIME);
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

    private boolean looksUnsupported(String message) {
        var value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return value.contains("-32601")
                || value.contains("method not found")
                || value.contains("not found")
                || value.contains("unsupported")
                || value.contains("unknown method");
    }

    static String challengePassword(String username, String realm, String password, String random) {
        var pwd1 = md5(username + ":" + realm + ":" + password);
        return md5(username + ":" + random + ":" + pwd1);
    }

    static String md5(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is not available.", exception);
        }
    }

    private String sanitizeJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return safe(value)
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"<redacted>\"")
                .replaceAll("(?i)\"(UserID|CardNo|UserName|CardName)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"<redacted>\"")
                .replaceAll("(?i)\"PhotoData\"\\s*:\\s*\\[[^]]*]", "\"PhotoData\":[\"<base64-redacted>\"]")
                .replaceAll("(?i)\"FaceData\"\\s*:\\s*\\[[^]]*]", "\"FaceData\":[\"<redacted>\"]");
    }

    private String summary(JsonNode node) {
        return summarize(sanitizeJson(node == null ? "" : node.toString()));
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)(password|senha)=([^\\s,;&}]+)", "$1=***")
                .replaceAll("(?i)(WebClientHttpSessionID=)[^;\\s]+", "$1<redacted>");
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var digits = digits(value);
        var raw = digits.isBlank() ? value : digits;
        if (raw.length() <= 4) {
            return "****";
        }
        return raw.substring(0, 2) + "****" + raw.substring(raw.length() - 2);
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asText();
    }

    public enum FacePresence {
        PRESENT,
        ABSENT,
        UNSUPPORTED
    }

    public record Session(String host, String sessionId, String cookie, String username, Instant createdAt) {
    }

    public record UserFindResult(boolean found, int recordsCount, String responseSummary) {
    }

    public record AccessUserUpsertResult(String action, String method, String responseSummary) {
    }

    public record Rpc2CallResult(String method, String responseSummary) {
    }

    private record RawRpcResponse(JsonNode body, String cookie, int httpStatus) {
    }

    private record WebBootstrap(String cookie) {
    }

    private record RpcAttempt(String method, Object params) {
    }
}
