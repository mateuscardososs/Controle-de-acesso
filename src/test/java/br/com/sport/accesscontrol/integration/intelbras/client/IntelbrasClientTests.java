package br.com.sport.accesscontrol.integration.intelbras.client;

import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class IntelbrasClientTests {

    @Test
    void rpcLoginUsesChallengeDigestAndReturnsSession() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(200, """
                {"id":1,"session":12345,"params":{"realm":"Login to Intelbras","random":"abcdef"}}
                """);
        var success = stringResponse(200, """
                {"id":2,"result":true,"session":12345,"params":{"keepAliveInterval":20}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(success);

        var client = new IntelbrasRpcClient(httpClient, new ObjectMapper(), properties);
        var session = client.login("192.168.15.5", "admin", "secret");

        assertThat(session.sessionId()).isEqualTo("12345");
        assertThat(IntelbrasRpcClient.challengePassword("admin", "Login to Intelbras", "secret", "abcdef"))
                .isEqualTo(md5("admin:abcdef:" + md5("admin:Login to Intelbras:secret")));
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void digestAuthorizationMasksPasswordAndBuildsResponse() {
        var challenge = IntelbrasDigestAuth.parseChallenge(
                "Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\", opaque=\"opaque-value\""
        );

        var header = IntelbrasDigestAuth.authorizationHeader(
                "GET",
                URI.create("http://192.168.15.5/cgi-bin/magicBox.cgi?action=getSerialNo"),
                "admin",
                "secret",
                challenge
        );

        assertThat(header).startsWith("Digest ");
        assertThat(header).contains("username=\"admin\"");
        assertThat(header).contains("uri=\"/cgi-bin/magicBox.cgi?action=getSerialNo\"");
        assertThat(header).contains("qop=auth");
        assertThat(header).doesNotContain("secret");
        assertThat(header).doesNotContainPattern("response=\"[A-F0-9]{32}\"");
    }

    @Test
    void cgiClientRetriesWithDigestAuthAfter401ForMagicBoxEndpoints() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "type=SS 5531", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "sn=DRWL3903457HU", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "version=1.0.0", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        assertThat(client.getDeviceType("192.168.15.5", "admin", "admin123")).isEqualTo("SS 5531");
        assertThat(client.getSerialNo("192.168.15.5", "admin", "admin123")).isEqualTo("DRWL3903457HU");
        assertThat(client.getSoftwareVersion("192.168.15.5", "admin", "admin123")).isEqualTo("1.0.0");

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(6)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();
        assertThat(requests.get(1).headers().firstValue("Authorization")).hasValueSatisfying((header) -> {
            assertThat(header).startsWith("Digest ");
            assertThat(header).contains("username=\"admin\"");
            assertThat(header).doesNotContain("admin123");
        });
        assertThat(requests.get(3).headers().firstValue("Authorization")).isPresent();
        assertThat(requests.get(5).headers().firstValue("Authorization")).isPresent();
    }

    @Test
    void cgiClientUsesBioTEndpointsToUpsertUserAndReplaceFace() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "found=0", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "RecNo=10", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "OK", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "OK", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "Count=1\nUserID=16", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        client.upsertAccessUser(
                "192.168.15.5",
                "admin",
                "admin123",
                "16",
                "Alexandre16",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );
        client.replaceFace("192.168.15.5", "admin", "admin123", "16", "/9j/test");

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(10)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();

        assertThat(requests.get(1).uri().toString())
                .contains("/cgi-bin/recordFinder.cgi?action=find&name=AccessControlCard&condition.UserID=16");
        assertThat(requests.get(3).uri().toString())
                .contains("/cgi-bin/recordUpdater.cgi?action=insert&name=AccessControlCard")
                .contains("CardNo=16")
                .contains("CardStatus=0")
                .contains("UserID=16")
                .contains("CardName=Alexandre16")
                .contains("ValidDateStart=2026-05-20%2008%3A00%3A00")
                .contains("ValidDateEnd=2037-12-31%2023%3A59%3A59");
        assertThat(requests.get(5).uri().toString())
                .contains("/cgi-bin/FaceInfoManager.cgi?action=remove&UserID=16");
        assertThat(requests.get(7).method()).isEqualTo("POST");
        assertThat(requests.get(7).uri().toString())
                .contains("/cgi-bin/FaceInfoManager.cgi?action=add");
        assertThat(requests.get(7).headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(bodyOf(requests.get(7)))
                .contains("\"UserID\":\"16\"")
                .doesNotContain("\"UserName\"")
                .contains("\"PhotoData\":[\"/9j/test\"]");
    }

    @Test
    void cgiClientUpdatesExistingAccessUserWhenRecordFinderReturnsRecNo() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, """
                        found=1
                        records[0].RecNo=22
                        records[0].UserID=16
                        records[0].CardNo=16
                        """, Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "OK", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        client.upsertAccessUser(
                "192.168.15.5",
                "admin",
                "admin123",
                "16",
                "Alexandre16",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(4)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();
        assertThat(requests.get(3).uri().toString())
                .contains("/cgi-bin/recordUpdater.cgi?action=update&name=AccessControlCard")
                .contains("recno=22")
                .contains("CardNo=16")
                .contains("CardStatus=0")
                .contains("UserID=16")
                .contains("ValidDateStart=2026-05-20%2008%3A00%3A00")
                .contains("ValidDateEnd=2037-12-31%2023%3A59%3A59");
    }

    @Test
    void cgiClientRetriesRecordUpdaterAsPostFormWhenDocumentedGetIsRejected() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "found=0", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(400, "Error\nBad Request!", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "RecNo=10", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        // Employee with a physical card — CardNo must be the tag number, NOT the CPF
        var response = client.upsertAccessUser(
                "192.168.15.5",
                "admin",
                "admin123",
                "05731650411",       // userId = CPF
                "8765432109",        // cardNo = physical tag (different from CPF)
                "mateus da silva cardoso",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        assertThat(response).isEqualTo("RecNo=10");
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(6)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();
        assertThat(requests.get(5).method()).isEqualTo("POST");
        assertThat(requests.get(5).uri().toString()).contains("/cgi-bin/recordUpdater.cgi");
        assertThat(requests.get(5).headers().firstValue("Content-Type"))
                .hasValue("application/x-www-form-urlencoded; charset=UTF-8");
        assertThat(bodyOf(requests.get(5)))
                .contains("action=insert")
                .contains("name=AccessControlCard")
                .contains("CardNo=8765432109")        // physical card, NOT CPF
                .doesNotContain("CardNo=05731650411") // CPF must NEVER be the CardNo
                .contains("CardStatus=0")
                .contains("CardName=mateus%20da%20silva%20cardoso")
                .contains("UserID=05731650411");
    }

    @Test
    void cgiClientDerivesCardNoFromCpfWhenNoPhysicalCard() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "found=0", Map.of()))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "OK", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        // Guest with CPF as userId but no physical card uses CPF without the last digit as CardNo.
        client.upsertAccessUser(
                "192.168.15.5", "admin", "admin123",
                "06331315470",  // userId = CPF
                "",              // cardNo = blank → no physical card
                "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(4)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();
        var insertUri = requests.get(3).uri().toString();
        assertThat(insertUri)
                .contains("UserID=06331315470")
                .contains("CardNo=0633131547")
                .doesNotContain("CardNo=06331315470")
                .contains("CardName=Visitante%20Real");
    }

    @Test
    void faceOnlyBaseInsertUsesCpf10CardNoDifferentFromFullCpf() throws Exception {
        var httpClient = mock(HttpClient.class);
        var notFound = stringResponse(200, "found=0", Map.of());
        var ok = stringResponse(200, "OK", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(notFound)
                .thenReturn(ok);

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        var result = client.upsertFaceOnlyAccessUser(
                "192.168.15.5", "admin", "admin123",
                "06331315470",
                "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        assertThat(result.usedTemporaryCardNo()).isFalse();
        assertThat(result.temporaryCardNo()).isEqualTo("0633131547");
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var insertUri = requestCaptor.getAllValues().get(1).uri().toString();
        assertThat(insertUri)
                .contains("/cgi-bin/recordUpdater.cgi?action=insert&name=AccessControlCard")
                .contains("CardNo=0633131547")
                .contains("CardName=Visitante%20Real")
                .contains("UserID=06331315470")
                .doesNotContain("CardNo=06331315470");
    }

    @Test
    void faceOnlyBaseUpdateUsesExistingRecordInsteadOfInsert() throws Exception {
        var httpClient = mock(HttpClient.class);
        var existing = stringResponse(200, """
                found=1
                records[0].RecNo=22
                records[0].UserID=06331315470
                records[0].CardNo=2391496943
                """, Map.of());
        var ok = stringResponse(200, "OK", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(existing)
                .thenReturn(ok);

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        var result = client.upsertFaceOnlyAccessUser(
                "192.168.15.5", "admin", "admin123",
                "06331315470",
                "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        assertThat(result.action()).isEqualTo("update");
        assertThat(result.usedTemporaryCardNo()).isFalse();
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var updateUri = requestCaptor.getAllValues().get(1).uri().toString();
        assertThat(updateUri)
                .contains("/cgi-bin/recordUpdater.cgi?action=update&name=AccessControlCard")
                .contains("recno=22")
                .contains("CardNo=0633131547")
                .contains("UserID=06331315470")
                .doesNotContain("action=insert")
                .doesNotContain("CardNo=06331315470");
    }

    @Test
    void faceOnlyInsertBadRequestDoesNotReturnFalseSuccess() throws Exception {
        var httpClient = mock(HttpClient.class);
        var notFound = stringResponse(200, "found=0", Map.of());
        var badRequest = stringResponse(400, "Error Bad Request", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(notFound)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(notFound);

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        assertThatThrownBy(() -> client.upsertFaceOnlyAccessUser(
                "192.168.15.5", "admin", "admin123",
                "06331315470",
                "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        )).isInstanceOf(IntelbrasIntegrationException.class)
                .hasMessageContaining("status=400");
    }

    @Test
    void faceOnlyInsertRetriesAfterRemovingStaleCardNoOwner() throws Exception {
        var httpClient = mock(HttpClient.class);
        var notFound = stringResponse(200, "found=0", Map.of());
        var badRequest = stringResponse(400, "Error Bad Request", Map.of());
        var conflictFound = stringResponse(200, """
                found=1
                records[0].RecNo=88
                records[0].UserID=00000000001
                records[0].CardNo=0633131547
                """, Map.of());
        var ok = stringResponse(200, "OK", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(notFound)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(badRequest)
                .thenReturn(conflictFound)
                .thenReturn(ok)
                .thenReturn(ok);

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        var result = client.upsertFaceOnlyAccessUser(
                "192.168.15.5", "admin", "admin123",
                "06331315470",
                "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        assertThat(result.action()).isEqualTo("insert_after_conflict_resolution");
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(9)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var urls = requestCaptor.getAllValues().stream()
                .map(request -> request.uri().toString())
                .toList();
        assertThat(urls).anySatisfy(url -> assertThat(url)
                .contains("recordFinder.cgi")
                .contains("condition.CardNo=0633131547"));
        assertThat(urls).anySatisfy(url -> assertThat(url)
                .contains("recordUpdater.cgi?action=remove")
                .contains("recno=88"));
        assertThat(urls.getLast()).contains("recordUpdater.cgi?action=insert");
    }

    @Test
    void cgiClientDoesNotInsertAgainWhenRecordFinderFindsUserWithoutRecNo() throws Exception {
        var httpClient = mock(HttpClient.class);
        var properties = properties();
        var challenge = stringResponse(401, "", Map.of(
                "WWW-Authenticate", List.of("Digest realm=\"Intelbras\", nonce=\"abc\", qop=\"auth\"")
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(challenge)
                .thenReturn(stringResponse(200, "found=1", Map.of()));

        var client = new IntelbrasCgiClient(httpClient, properties, new ObjectMapper());

        var result = client.upsertAccessUser(
                "192.168.15.5",
                "admin",
                "admin123",
                "16",
                "Alexandre16",
                LocalDateTime.of(2026, 5, 20, 8, 0),
                LocalDateTime.of(2037, 12, 31, 23, 59, 59)
        );

        assertThat(result).isEqualTo("EXISTS_WITHOUT_RECNO");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void clearCardNoForUserReportsClearedWhenReReadShowsEmptyCardNo() throws Exception {
        // DELETE flow: 3 HTTP calls (no digest challenge):
        // 1. lookup-by-UserID (before) — CardNo=2391496943, recNo=22
        // 2. DELETE AccessControlCard (action=remove) — OK
        // 3. verify after DELETE — record gone (found=0)
        var httpClient = mock(HttpClient.class);
        var cardPopulated = stringResponse(200, "found=1\nrecords[0].RecNo=22\nrecords[0].UserID=06331315470\nrecords[0].CardNo=2391496943\n", Map.of());
        var ok = stringResponse(200, "OK", Map.of());
        var notFound = stringResponse(200, "found=0\n", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(cardPopulated) // 1. lookup-by-UserID before
                .thenReturn(ok)           // 2. DELETE action=remove
                .thenReturn(notFound);    // 3. verify: record gone

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());
        var result = client.clearCardNoForUser("192.168.15.5", "admin", "admin123",
                "06331315470", "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0), LocalDateTime.of(2037, 12, 31, 23, 59, 59));

        assertThat(result.cleared()).isTrue();
        assertThat(result.cardNoBefore()).isEqualTo("2391496943");
        assertThat(result.cardNoAfter()).isEmpty();
        assertThat(result.userExists()).isFalse(); // record deleted — AccessControlCard no longer present
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getAllValues().stream().map(r -> r.uri().toString()).toList())
                .anyMatch(uri -> uri.contains("action=remove") && uri.contains("name=AccessControlCard") && uri.contains("recno=22"));
    }

    @Test
    void clearCardNoForUserReportsNotClearedWhenDeleteFails() throws Exception {
        // DELETE returns an error body → ensureCgiBodyAccepted throws → cleared=false.
        // 2 HTTP calls (no digest challenge):
        // 1. lookup-by-UserID (before) — CardNo=2391496943, recNo=22
        // 2. DELETE action=remove — body contains "error" → rejected
        var httpClient = mock(HttpClient.class);
        var cardPopulated = stringResponse(200, "found=1\nrecords[0].RecNo=22\nrecords[0].UserID=06331315470\nrecords[0].CardNo=2391496943\n", Map.of());
        var deleteError = stringResponse(200, "error", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(cardPopulated) // 1. lookup-by-UserID
                .thenReturn(deleteError);  // 2. DELETE rejected by firmware

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());
        var result = client.clearCardNoForUser("192.168.15.5", "admin", "admin123",
                "06331315470", "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0), LocalDateTime.of(2037, 12, 31, 23, 59, 59));

        assertThat(result.cleared()).isFalse();
        assertThat(result.cardNoBefore()).isEqualTo("2391496943");
        assertThat(result.cardNoAfter()).isEqualTo("2391496943");
        assertThat(result.userExists()).isTrue();
        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getAllValues().stream().map(r -> r.uri().toString()).toList())
                .anyMatch(uri -> uri.contains("action=remove") && uri.contains("name=AccessControlCard"));
    }

    @Test
    void clearCardNoForUserReportsNotClearedWhenRecordStillExistsAfterDelete() throws Exception {
        // DELETE returns OK but verify shows record still present → cleared=false.
        // 3 HTTP calls (no digest challenge):
        // 1. lookup-by-UserID (before) — CardNo=2391496943, recNo=22
        // 2. DELETE action=remove — OK (firmware returns 200 but keeps the record)
        // 3. verify after DELETE — record still present
        var httpClient = mock(HttpClient.class);
        var stillPopulated = stringResponse(200, "found=1\nrecords[0].RecNo=22\nrecords[0].UserID=06331315470\nrecords[0].CardNo=2391496943\n", Map.of());
        var ok = stringResponse(200, "OK", Map.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stillPopulated) // 1. lookup-by-UserID before
                .thenReturn(ok)             // 2. DELETE — accepted but record persisted
                .thenReturn(stillPopulated);// 3. verify: still there

        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());
        var result = client.clearCardNoForUser("192.168.15.5", "admin", "admin123",
                "06331315470", "Visitante Real",
                LocalDateTime.of(2026, 5, 20, 8, 0), LocalDateTime.of(2037, 12, 31, 23, 59, 59));

        assertThat(result.cleared()).isFalse();
        assertThat(result.cardNoBefore()).isEqualTo("2391496943");
        assertThat(result.cardNoAfter()).isEqualTo("2391496943");
        assertThat(result.userExists()).isTrue();
        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void parsesRecordFinderIndexedFields() {
        var body = """
                found=2
                records[0].CardName=Mateus
                records[0].UserID=123
                records[0].Status=1
                records[1].CardName=Visitante
                records[1].UserID=456
                records[1].Status=0
                """;

        var records = IntelbrasRecordFinderParser.parseRecords(body);

        assertThat(records).hasSize(2);
        assertThat(records.getFirst()).containsEntry("CardName", "Mateus");
        assertThat(records.getFirst()).containsEntry("UserID", 123L);
        assertThat(records.get(1)).containsEntry("Status", 0L);
    }

    @Test
    void cgiBodyContainingErrorSubstringIsAccepted() throws Exception {
        // Regressão de falso-negativo: corpo HTTP 200 benigno que contém "error"/"failed" como
        // substring NÃO deve ser tratado como rejeição (antes, era → SYNC_FAILED com usuário criado).
        var httpClient = mock(HttpClient.class);
        var benign = stringResponse(200, "{\"result\":true,\"errorCount\":0,\"failedList\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(benign);
        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        assertThatCode(() -> client.removeFace("192.168.15.5", "admin", "secret", "16"))
                .doesNotThrowAnyException();
    }

    @Test
    void cgiBodyWithExplicitErrorTokenIsRejected() throws Exception {
        // Rejeição genuína: corpo exatamente "error" continua sendo recusado.
        var httpClient = mock(HttpClient.class);
        var rejected = stringResponse(200, "error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rejected);
        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        assertThatThrownBy(() -> client.removeFace("192.168.15.5", "admin", "secret", "16"))
                .isInstanceOf(IntelbrasIntegrationException.class);
    }

    @Test
    void cgiHttp200HtmlFromWebInterfaceIsRejected() throws Exception {
        var httpClient = mock(HttpClient.class);
        var html = stringResponse(200, """
                <!doctype html>
                <html><head><title>Intelbras</title></head><body>Login</body></html>
                """, Map.of("Content-Type", List.of("text/html; charset=utf-8")));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(html);
        var client = new IntelbrasCgiClient(httpClient, properties(), new ObjectMapper());

        assertThatThrownBy(() -> client.getDeviceType("192.168.15.5", "admin", "secret"))
                .isInstanceOf(IntelbrasIntegrationException.class)
                .hasMessageContaining("retornou HTML");
    }

    private IntelbrasProperties properties() {
        var properties = new IntelbrasProperties();
        properties.setConnectionTimeout(Duration.ofMillis(100));
        properties.setReadTimeout(Duration.ofMillis(100));
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

    private String md5(String value) throws Exception {
        var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        var builder = new StringBuilder();
        for (byte item : digest) {
            builder.append(String.format("%02X", item));
        }
        return builder.toString();
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
