package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IntelbrasRpcClient {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasRpcClient.class);
    private static final String RPC_PATH = "/RPC2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final IntelbrasProperties properties;
    private final AtomicInteger ids = new AtomicInteger(1);

    @Autowired
    public IntelbrasRpcClient(ObjectMapper objectMapper, IntelbrasProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.getConnectionTimeout())
                .build(), objectMapper, properties);
    }

    IntelbrasRpcClient(HttpClient httpClient, ObjectMapper objectMapper, IntelbrasProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public IntelbrasRpcSession login(String host, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IntelbrasIntegrationException("Intelbras RPC credentials are not configured.");
        }

        var maskedHost = IntelbrasHttpSupport.maskHost(host);
        var challenge = postRpc(host, (String) null, "global.login", Map.of(
                "userName", username,
                "password", "",
                "clientType", "Web3.0"
        ));

        var realm = requiredText(challenge.at("/params/realm"), "realm", maskedHost);
        var random = requiredText(challenge.at("/params/random"), "random", maskedHost);
        var challengeSession = text(challenge.get("session"));
        var digestPassword = challengePassword(username, realm, password, random);

        var login = postRpc(host, challengeSession, "global.login", Map.of(
                "userName", username,
                "password", digestPassword,
                "clientType", "Web3.0",
                "authorityType", "Default"
        ));

        if (login.has("result") && !login.get("result").asBoolean()) {
            throw new IntelbrasIntegrationException("Intelbras RPC login rejected by host " + maskedHost + ".");
        }
        var sessionId = text(login.get("session"));
        if (sessionId == null) {
            sessionId = challengeSession;
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IntelbrasIntegrationException("Intelbras RPC login did not return a session for host " + maskedHost + ".");
        }
        return new IntelbrasRpcSession(host, sessionId, username, Instant.now());
    }

    public JsonNode keepAlive(IntelbrasRpcSession session) {
        return postRpc(session.host(), session, "global.keepAlive", Map.of(
                "timeout", 300,
                "active", true
        ));
    }

    public JsonNode postRpc(String host, IntelbrasRpcSession session, String method, Object params) {
        return postRpc(host, session == null ? null : session.sessionId(), method, params);
    }

    public JsonNode postRpc(String host, String sessionId, String method, Object params) {
        var uri = IntelbrasHttpSupport.uri(host, RPC_PATH);
        var requestBody = requestBody(sessionId, method, params);
        var request = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("intelbras_rpc_http_request method=POST host={} endpoint={} rpc_method={} request_headers={} request_body={}",
                    IntelbrasHttpSupport.maskHost(host), RPC_PATH, method, sanitizeHeaders(request.headers().map()),
                    sanitizeBody(requestBody));
            var response = sendWithRetry(request);
            log.info("intelbras_rpc_http_response method=POST host={} endpoint={} rpc_method={} status={} response_headers={} response_body={}",
                    IntelbrasHttpSupport.maskHost(host), RPC_PATH, method, response.statusCode(),
                    sanitizeHeaders(response.headers().map()), sanitizeBody(response.body()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IntelbrasIntegrationException("Intelbras RPC request failed for host "
                        + IntelbrasHttpSupport.maskHost(host) + " endpoint=" + RPC_PATH + " method=" + method
                        + " status=" + response.statusCode() + " body=" + sanitizeBody(response.body()) + ".");
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IntelbrasIntegrationException("Intelbras RPC response was invalid for host "
                    + IntelbrasHttpSupport.maskHost(host) + ".", exception);
        }
    }

    private String requestBody(String sessionId, String method, Object params) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("method", method);
        root.set("params", objectMapper.valueToTree(params == null ? Map.of() : params));
        root.put("id", ids.getAndIncrement());
        if (sessionId != null && !sessionId.isBlank()) {
            putSession(root, sessionId);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IntelbrasIntegrationException("Could not serialize Intelbras RPC request.", exception);
        }
    }

    private void putSession(ObjectNode root, String sessionId) {
        if (sessionId.chars().allMatch(Character::isDigit)) {
            try {
                root.put("session", Long.parseLong(sessionId));
                return;
            } catch (NumberFormatException ignored) {
                // Fall back to string session identifiers used by some firmwares.
            }
        }
        root.put("session", sessionId);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        RuntimeException lastRuntime = null;
        IOException lastIo = null;
        InterruptedException lastInterrupted = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterrupted = exception;
                break;
            } catch (IOException exception) {
                lastIo = exception;
            } catch (RuntimeException exception) {
                lastRuntime = exception;
            }
        }
        if (lastInterrupted != null) {
            throw new IntelbrasIntegrationException("Intelbras RPC request was interrupted.", lastInterrupted);
        }
        if (lastIo != null) {
            throw new IntelbrasIntegrationException("Intelbras RPC request failed.", lastIo);
        }
        throw new IntelbrasIntegrationException("Intelbras RPC request failed.", lastRuntime);
    }

    private String requiredText(JsonNode node, String field, String maskedHost) {
        var value = text(node);
        if (value == null || value.isBlank()) {
            throw new IntelbrasIntegrationException("Intelbras RPC login challenge missing " + field
                    + " for host " + maskedHost + ".");
        }
        return value;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    static String challengePassword(String username, String realm, String password, String random) {
        var pwd1 = md5(username + ":" + realm + ":" + password);
        return md5(username + ":" + random + ":" + pwd1);
    }

    static String md5(String value) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is not available.", exception);
        }
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
        return body.replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"<redacted>\"");
    }
}
