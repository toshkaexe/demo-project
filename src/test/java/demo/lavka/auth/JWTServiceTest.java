package demo.lavka.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import demo.lavka.config.JwtConfig;
import demo.lavka.model.TokenPair;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JWTService: проверка подписи и срока токена")
class JWTServiceTest {

    private static final String SECRET = "test-secret-only-for-tests-0123456789abcdef";

    private final JWTService jwtService = new JWTService(
            new JwtConfig(SECRET, Duration.ofMinutes(15), Duration.ofDays(30)));

    @Test
    @DisplayName("Наш токен с верной подписью принимается")
    void acceptsTokenSignedWithOurKey() {
        TokenPair tokens = jwtService.issueTokens(42L);

        assertThat(jwtService.verifyAccess(tokens.accessToken()))
                .hasValueSatisfying(token -> assertThat(token.userId()).isEqualTo(42L));
        assertThat(jwtService.verifyRefresh(tokens.refreshToken())).isPresent();
    }

    @Test
    @DisplayName("Токен с подменённым payload (sub=42 → sub=1) отклоняется")
    void rejectsTokenWithChangedPayload() {
        String token = jwtService.issueTokens(42L).accessToken();
        String[] parts = token.split("\\.");

        // pretend to be user 1, keep the original signature
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace("\"sub\":\"42\"", "\"sub\":\"1\"");
        assertThat(forgedPayload).isNotEqualTo(payload);
        String forged = parts[0] + "." + base64Url(forgedPayload) + "." + parts[2];

        assertThat(jwtService.verifyAccess(forged)).isEmpty();
    }

    @Test
    @DisplayName("Токен с изменённым символом подписи отклоняется")
    void rejectsTokenWithChangedSignature() {
        String token = jwtService.issueTokens(42L).accessToken();
        String[] parts = token.split("\\.");
        char last = parts[2].charAt(0);
        String forged = parts[0] + "." + parts[1] + "." + (last == 'A' ? 'B' : 'A') + parts[2].substring(1);

        assertThat(jwtService.verifyAccess(forged)).isEmpty();
    }

    @Test
    @DisplayName("Токен, подписанный чужим ключом, отклоняется")
    void rejectsTokenSignedWithAnotherKey() {
        String foreign = Jwts.builder()
                .subject("42")
                .claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("some-other-secret-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwtService.verifyAccess(foreign)).isEmpty();
    }


    @Test
    @DisplayName("Токен без подписи (alg: none) отклоняется")
    void rejectsUnsignedToken() {
        // "alg": "none" and no signature at all
        String unsigned = base64Url("{\"alg\":\"none\"}") + "."
                + base64Url("{\"sub\":\"42\",\"typ\":\"access\",\"exp\":" + (System.currentTimeMillis() / 1000 + 60) + "}") + ".";

        assertThat(jwtService.verifyAccess(unsigned)).isEmpty();
    }

    @Test
    @DisplayName("Токен с истёкшим сроком (exp в прошлом) отклоняется, даже с верной подписью")
    void rejectsExpiredToken() {
        JWTService expiring = new JWTService(new JwtConfig(SECRET, Duration.ofSeconds(-1), Duration.ofSeconds(-1)));
        TokenPair tokens = expiring.issueTokens(42L);

        assertThat(jwtService.verifyAccess(tokens.accessToken())).isEmpty();
        assertThat(jwtService.verifyRefresh(tokens.refreshToken())).isEmpty();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

}
