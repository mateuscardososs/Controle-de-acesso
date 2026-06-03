package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class IntelbrasRpc2ClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rpc2LoginUsesChallengeDigestCookieAndReturnsSession() throws Exception {
        var httpClient = mock(HttpClient.class);
        var bootstrap = stringResponse(200, """
                <!doctype html><html><title>WEB</title></html>
                """, Map.of("Set-Cookie", List.of("BootCookie=boot; Path=/")));
        // Realistic firmware challenge: HTTP 200 with result:false + error JUNTO com realm/random.
        // O login deve tolerar isso (não tratar como falha) e seguir para o commit.
        var challenge = stringResponse(200, """
                {"id":1,"session":12345,"result":false,"error":{"code":268632079,"message":"Need challenge"},"params":{"realm":"Login to Intelbras","random":"abcdef","encryption":"Default"}}
                """, Map.of("Set-Cookie", List.of("WebClientHttpSessionID=challenge-cookie; Path=/")));
        var success = stringResponse(200, """
                {"id":2,"result":true,"session":12345,"params":{"keepAliveInterval":20}}
                """, Map.of("Set-Cookie", List.of("WebClientHttpSessionID=final-cookie; Path=/")));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(bootstrap)
                .thenReturn(challenge)
                .thenReturn(success);

        var client = client(httpClient);
        var session = client.login("192.168.15.5", "admin", "secret");

        assertThat(session.sessionId()).isEqualTo("12345");
        assertThat(session.cookie()).isEqualTo("BootCookie=boot; WebClientHttpSessionID=final-cookie");

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).uri().getPath()).isEqualTo("/");
        assertThat(requests.get(1).uri().getPath()).isEqualTo("/RPC2_Login");
        assertThat(requests.get(2).uri().getPath()).isEqualTo("/RPC2_Login");
        assertThat(requests.get(1).headers().firstValue("Cookie"))
                .hasValue("BootCookie=boot");
        assertThat(requests.get(2).headers().firstValue("Cookie"))
                .hasValue("BootCookie=boot; WebClientHttpSessionID=challenge-cookie");
        assertThat(requests.get(1).headers().firstValue("Accept"))
                .hasValue("application/json, text/plain, */*");
        assertThat(requests.get(1).headers().firstValue("Origin"))
                .hasValue("http://192.168.15.5");
        assertThat(requests.get(1).headers().firstValue("Referer"))
                .hasValue("http://192.168.15.5/");

        var challengeBody = jsonBody(requests.get(1));
        assertThat(challengeBody.get("method").asText()).isEqualTo("global.login");
        assertThat(challengeBody.at("/params/userName").asText()).isEqualTo("admin");
        assertThat(challengeBody.at("/params/password").asText()).isBlank();

        var loginBody = jsonBody(requests.get(2));
        assertThat(loginBody.get("session").asLong()).isEqualTo(12345L);
        assertThat(loginBody.at("/params/password").asText())
                .isEqualTo(IntelbrasRpc2Client.challengePassword("admin", "Login to Intelbras", "secret", "abcdef"))
                .doesNotContain("secret");
    }

    @Test
    void rpc2Http200InternalErrorFails() throws Exception {
        var httpClient = mock(HttpClient.class);
        var error = stringResponse(200, """
                {"id":1,"result":false,"error":{"code":268632079,"message":"Invalid method"}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(error);
        var client = client(httpClient);
        var session = session();

        assertThatThrownBy(() -> client.postRpc(session, "AccessUser.insertMulti", Map.of()))
                .isInstanceOf(IntelbrasIntegrationException.class)
                .hasMessageContaining("internal error")
                .hasMessageContaining("AccessUser.insertMulti");
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().uri().getPath()).isEqualTo("/RPC2");
    }

    @Test
    void accessUserStartFindReportsFoundAndMissing() throws Exception {
        var httpClient = mock(HttpClient.class);
        var foundResponse = stringResponse(200, """
                {"id":1,"result":true,"params":{"count":1,"UserList":[{"UserID":"12345678901","UserName":"Colaborador"}]}}
                """);
        var missingResponse = stringResponse(200, """
                {"id":2,"result":true,"params":{"count":0,"UserList":[]}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(foundResponse)
                .thenReturn(missingResponse);
        var client = client(httpClient);

        var found = client.findUser(session(), "12345678901");
        var missing = client.findUser(session(), "00000000000");

        assertThat(found.found()).isTrue();
        assertThat(found.recordsCount()).isEqualTo(1);
        assertThat(missing.found()).isFalse();
        assertThat(missing.recordsCount()).isZero();
    }

    @Test
    void userCardFaceAndVerificationPayloadsKeepSameUserId() throws Exception {
        var httpClient = mock(HttpClient.class);
        var missingUser = stringResponse(200, """
                {"id":1,"result":true,"params":{"count":0,"UserList":[]}}
                """);
        var userInserted = stringResponse(200, """
                {"id":2,"result":true,"params":{"ErrorList":[]}}
                """);
        var cardInserted = stringResponse(200, """
                {"id":3,"result":true,"params":{"ErrorList":[]}}
                """);
        var faceInserted = stringResponse(200, """
                {"id":4,"result":true,"params":{"ErrorList":[]}}
                """);
        var userPresent = stringResponse(200, """
                {"id":5,"result":true,"params":{"count":1,"UserList":[{"UserID":"12345678901"}]}}
                """);
        var cardPresent = stringResponse(200, """
                {"id":6,"result":true,"params":{"count":1,"CardList":[{"UserID":"12345678901","CardNo":"8765432109"}]}}
                """);
        var facePresent = stringResponse(200, """
                {"id":7,"result":true,"params":{"count":1,"FaceList":[{"UserID":"12345678901"}]}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(missingUser)
                .thenReturn(userInserted)
                .thenReturn(cardInserted)
                .thenReturn(faceInserted)
                .thenReturn(userPresent)
                .thenReturn(cardPresent)
                .thenReturn(facePresent);
        var client = client(httpClient);
        var session = session();
        var userId = "12345678901";

        var upsert = client.upsertUser(session, userId, "8765432109", "Colaborador",
                LocalDateTime.of(2026, 6, 1, 9, 30),
                LocalDateTime.of(2026, 7, 16, 9, 30));
        var card = client.sendCard(session, userId, "8765-432-109");
        var face = client.sendFace(session, userId, "/9j/photo");

        assertThat(client.isUserPresent(session, userId)).isTrue();
        assertThat(client.isCardAssociatedWithUser(session, userId, "8765432109")).isTrue();
        assertThat(client.verifyFace(session, userId)).isEqualTo(IntelbrasRpc2Client.FacePresence.PRESENT);
        assertThat(upsert.method()).isEqualTo("AccessUser.insertMulti");
        assertThat(card.method()).isEqualTo("AccessCard.insertMulti");
        assertThat(face.method()).isEqualTo("AccessFace.insertMulti");

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(7)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var bodies = requestCaptor.getAllValues().stream()
                .map(request -> {
                    try {
                        return jsonBody(request);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();

        assertThat(bodies.get(0).get("method").asText()).isEqualTo("AccessUser.startFind");
        assertThat(bodies.get(0).at("/params/Condition/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(1).get("method").asText()).isEqualTo("AccessUser.insertMulti");
        assertThat(bodies.get(1).at("/params/UserList/0/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(1).at("/params/UserList/0/ValidFrom").asText()).isEqualTo("2026-06-01 09:30:00");
        assertThat(bodies.get(1).at("/params/UserList/0/ValidTo").asText()).isEqualTo("2026-07-16 09:30:00");
        assertThat(bodies.get(2).get("method").asText()).isEqualTo("AccessCard.insertMulti");
        assertThat(bodies.get(2).at("/params/CardList/0/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(2).at("/params/CardList/0/CardNo").asText()).isEqualTo("8765432109");
        assertThat(bodies.get(3).get("method").asText()).isEqualTo("AccessFace.insertMulti");
        assertThat(bodies.get(3).at("/params/FaceList/0/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(3).at("/params/FaceList/0/PhotoData/0").asText()).isEqualTo("/9j/photo");
        assertThat(bodies.get(4).at("/params/Condition/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(5).at("/params/Condition/UserID").asText()).isEqualTo(userId);
        assertThat(bodies.get(6).at("/params/Condition/UserID").asText()).isEqualTo(userId);
    }

    @Test
    void rpc2LoginChallengeWithoutRealmOrRandomFailsWithDetail() throws Exception {
        var httpClient = mock(HttpClient.class);
        var bootstrap = stringResponse(200, """
                <!doctype html><html><title>WEB</title></html>
                """);
        var challenge = stringResponse(200, """
                {"id":1,"result":false,"error":{"code":268632079,"message":"Need challenge"},"params":{"encryption":"Default"}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(bootstrap)
                .thenReturn(challenge);
        var client = client(httpClient);

        assertThatThrownBy(() -> client.login("192.168.15.5", "admin", "secret"))
                .isInstanceOf(IntelbrasIntegrationException.class)
                .hasMessageContaining("LOGIN_CHALLENGE")
                .hasMessageContaining("realm/random");
        // Bootstrap + challenge; não avança para o commit.
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rpc2LoginCommitWithoutSessionFailsWithDetail() throws Exception {
        var httpClient = mock(HttpClient.class);
        var bootstrap = stringResponse(200, """
                <!doctype html><html><title>WEB</title></html>
                """);
        var challenge = stringResponse(200, """
                {"id":1,"result":false,"error":{"code":268632079,"message":"Need challenge"},"params":{"realm":"Login to Intelbras","random":"abcdef"}}
                """, Map.of("Set-Cookie", List.of("WebClientHttpSessionID=challenge-cookie; Path=/")));
        var rejected = stringResponse(200, """
                {"id":2,"result":false,"error":{"code":268632085,"message":"Password error"}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(bootstrap)
                .thenReturn(challenge)
                .thenReturn(rejected);
        var client = client(httpClient);

        assertThatThrownBy(() -> client.login("192.168.15.5", "admin", "wrong"))
                .isInstanceOf(IntelbrasIntegrationException.class)
                .hasMessageContaining("LOGIN_COMMIT");
    }

    private IntelbrasRpc2Client client(HttpClient httpClient) {
        return new IntelbrasRpc2Client(httpClient, properties(), objectMapper);
    }

    private IntelbrasRpc2Client.Session session() {
        return new IntelbrasRpc2Client.Session(
                "192.168.15.5",
                "12345",
                "WebClientHttpSessionID=session-cookie",
                "admin",
                Instant.now()
        );
    }

    private IntelbrasProperties properties() {
        var properties = new IntelbrasProperties();
        properties.setConnectionTimeout(Duration.ofMillis(100));
        properties.setReadTimeout(Duration.ofMillis(100));
        properties.setRetryAttempts(1);
        properties.setRetryBackoff(Duration.ZERO);
        return properties;
    }

    private HttpResponse<String> stringResponse(int status, String body) {
        return stringResponse(status, body, Map.of());
    }

    private HttpResponse<String> stringResponse(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    private JsonNode jsonBody(HttpRequest request) throws Exception {
        return objectMapper.readTree(bodyOf(request));
    }

    private String bodyOf(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var chunks = new ArrayList<ByteBuffer>();
        var done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                chunks.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        done.await(1, TimeUnit.SECONDS);
        var size = chunks.stream().mapToInt(ByteBuffer::remaining).sum();
        var bytes = new byte[size];
        var offset = 0;
        for (ByteBuffer chunk : chunks) {
            var length = chunk.remaining();
            chunk.get(bytes, offset, length);
            offset += length;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
