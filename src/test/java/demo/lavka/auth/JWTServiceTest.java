package demo.lavka.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import demo.lavka.auth.InvalidTokenException.Reason;
import demo.lavka.config.JwtConfig;
import demo.lavka.model.TokenPair;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JWTService: проверка подписи и срока токена")
class JWTServiceTest {

    private static final String SECRET = "test-secret-only-for-tests-0123456789abcdef";

    private final JWTService jwtService = new JWTService(
            new JwtConfig(SECRET, Duration.ofMinutes(15), Duration.ofDays(30)));

    @Test
    @DisplayName("Наш токен с верной подписью принимается")
    void acceptsTokenSignedWithOurKey() {
        TokenPair tokens = jwtService.issueTokens(42L);

        assertThat(jwtService.validateToken(tokens.accessToken(), TokenType.ACCESS).userId()).isEqualTo(42L);
        assertThat(jwtService.validateToken(tokens.refreshToken(), TokenType.REFRESH).userId()).isEqualTo(42L);
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

        assertRejected(forged, TokenType.ACCESS, Reason.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("Токен с изменённым символом подписи отклоняется")
    void rejectsTokenWithChangedSignature() {
        String token = jwtService.issueTokens(42L).accessToken();
        String[] parts = token.split("\\.");
        char last = parts[2].charAt(0);
        String forged = parts[0] + "." + parts[1] + "." + (last == 'A' ? 'B' : 'A') + parts[2].substring(1);

        assertRejected(forged, TokenType.ACCESS, Reason.BAD_SIGNATURE);
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

        assertRejected(foreign, TokenType.ACCESS, Reason.BAD_SIGNATURE);
    }


    @Test
    @DisplayName("Токен без подписи (alg: none) отклоняется")
    void rejectsUnsignedToken() {
        // "alg": "none" and no signature at all
        String unsigned = base64Url("{\"alg\":\"none\"}") + "."
                + base64Url("{\"sub\":\"42\",\"typ\":\"access\",\"exp\":" + (System.currentTimeMillis() / 1000 + 60) + "}") + ".";

        assertRejected(unsigned, TokenType.ACCESS, Reason.MALFORMED);
    }

    @Test
    @DisplayName("Токен с истёкшим сроком (exp в прошлом) отклоняется, даже с верной подписью")
    void rejectsExpiredToken() {
        JWTService expiring = new JWTService(new JwtConfig(SECRET, Duration.ofSeconds(-1), Duration.ofSeconds(-1)));
        TokenPair tokens = expiring.issueTokens(42L);

        assertRejected(tokens.accessToken(), TokenType.ACCESS, Reason.EXPIRED);
        assertRejected(tokens.refreshToken(), TokenType.REFRESH, Reason.EXPIRED);
        assertThatThrownBy(() -> jwtService.validateToken(tokens.accessToken(), TokenType.ACCESS))
                .hasMessage("Token expired");
    }

    @Test
    @DisplayName("Для logout истёкший токен принимается, но подпись по-прежнему проверяется")
    void ignoringExpiryStillChecksSignature() {
        JWTService expiring = new JWTService(new JwtConfig(SECRET, Duration.ofSeconds(-1), Duration.ofSeconds(-1)));
        String expired = expiring.issueTokens(42L).accessToken();

        assertThat(jwtService.validateTokenIgnoringExpiry(expired, TokenType.ACCESS).userId()).isEqualTo(42L);

        String[] parts = expired.split("\\.");
        String forged = parts[0] + "." + base64Url(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"42\"", "\"sub\":\"1\"")) + "." + parts[2];
        assertThatThrownBy(() -> jwtService.validateTokenIgnoringExpiry(forged, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).getReason())
                .isEqualTo(Reason.BAD_SIGNATURE);
    }

    /**
     * A refresh token must not open {@code /users/me}, and an access token must not be exchanged
     * for a new pair: the {@code typ} claim tells them apart.
     */
    @Test
    @DisplayName("Токен не того типа (refresh вместо access и наоборот) отклоняется")
    void rejectsTokenOfWrongType() {
        TokenPair tokens = jwtService.issueTokens(42L);

        assertRejected(tokens.refreshToken(), TokenType.ACCESS, Reason.WRONG_TYPE);
        assertRejected(tokens.accessToken(), TokenType.REFRESH, Reason.WRONG_TYPE);
    }

    /**
     * After logout (or after a refresh token was exchanged) the token is still correctly signed
     * and not expired, but must not be accepted anymore.
     */
    @Test
    @DisplayName("Отозванный токен отклоняется, хотя подпись и срок в порядке")
    void rejectsRevokedToken() {
        String token = jwtService.issueTokens(42L).accessToken();
        jwtService.revoke(jwtService.validateToken(token, TokenType.ACCESS));

        assertRejected(token, TokenType.ACCESS, Reason.REVOKED);
    }

    @Test
    @DisplayName("Мусор вместо токена отклоняется")
    void rejectsGarbage() {
        assertRejected("garbage", TokenType.ACCESS, Reason.MALFORMED);
        assertRejected("", TokenType.ACCESS, Reason.MALFORMED);
    }

    private void assertRejected(String token, TokenType type, Reason reason) {
        assertThatThrownBy(() -> jwtService.validateToken(token, type))
                .isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).getReason())
                .isEqualTo(reason);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

}
