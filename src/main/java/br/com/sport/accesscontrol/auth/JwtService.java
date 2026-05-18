package br.com.sport.accesscontrol.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;
    private final long expirationSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${app.security.jwt.secret:dev-only-change-this-secret-please-keep-it-long}") String secret,
            @Value("${app.security.jwt.expiration-seconds:28800}") long expirationSeconds
    ) {
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(UserPrincipal principal) {
        var now = Instant.now(clock);
        var header = Map.of("alg", "HS256", "typ", "JWT");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sub", principal.email());
        payload.put("uid", principal.id().toString());
        payload.put("name", principal.name());
        payload.put("roles", principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plusSeconds(expirationSeconds).getEpochSecond());

        var encodedHeader = encodeJson(header);
        var encodedPayload = encodeJson(payload);
        var unsigned = encodedHeader + "." + encodedPayload;
        return unsigned + "." + sign(unsigned);
    }

    public String subject(String token) {
        var payload = parseAndValidate(token);
        return (String) payload.get("sub");
    }

    public long expirationSeconds() {
        return expirationSeconds;
    }

    private Map<String, Object> parseAndValidate(String token) {
        try {
            var parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BadCredentialsException("Invalid JWT");
            }
            var unsigned = parts[0] + "." + parts[1];
            if (!constantTimeEquals(sign(unsigned), parts[2])) {
                throw new BadCredentialsException("Invalid JWT signature");
            }

            Map<String, Object> payload = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    }
            );
            var exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now(clock).getEpochSecond() >= exp) {
                throw new BadCredentialsException("Expired JWT");
            }
            return payload;
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadCredentialsException("Invalid JWT", exception);
        }
    }

    private String encodeJson(Object value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode JWT", exception);
        }
    }

    private String sign(String unsignedToken) {
        try {
            var mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign JWT", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigestHelper.equals(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class MessageDigestHelper {
        private MessageDigestHelper() {
        }

        static boolean equals(byte[] left, byte[] right) {
            return java.security.MessageDigest.isEqual(left, right);
        }
    }
}
