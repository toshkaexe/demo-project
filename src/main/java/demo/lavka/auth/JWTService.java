package demo.lavka.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

import demo.lavka.config.JwtProperties;
import demo.lavka.model.TokenPair;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies JWTs signed with HMAC-SHA
 * (HS256/384/512, picked by jjwt from the secret length).
 * <p>
 * Login and register return a pair: a short-lived access token and a long-lived refresh token.
 * Both carry the same session id ({@code sid}), so logout revokes the whole pair at once.
 * JWTs are stateless, so revoked sessions are kept on an in-memory denylist until the
 * refresh token would expire. A restart clears the denylist.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JWTService {

    static final String TYPE_CLAIM = "typ";
    static final String SESSION_CLAIM = "sid";
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final JwtProperties properties;
    private final JwtParser parser;

    // revoked session id -> when its refresh token expires
    private final Map<String, Instant> revokedSessions = new ConcurrentHashMap<>();

    public JWTService(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank() || secret.startsWith("${")
                || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET is missing or shorter than " + MIN_SECRET_BYTES
                    + " bytes: put it into .env (see .env.example) or set the JWT_SECRET environment variable");
        }
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    public TokenPair issueTokens(Long userId, String email) {
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        String accessToken = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("email", email)
                .claim(TYPE_CLAIM, ACCESS)
                .claim(SESSION_CLAIM, sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(key)
                .compact();
        String refreshToken = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(TYPE_CLAIM, REFRESH)
                .claim(SESSION_CLAIM, sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshTtl())))
                .signWith(key)
                .compact();
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * @return user id if the access token is valid, not expired and its session is not revoked
     */
    public Optional<Long> findUserId(String accessToken) {
        return parseActive(accessToken, ACCESS)
                .map(claims -> Long.valueOf(claims.getSubject()));
    }

    /**
     * Revokes the session of the given access token, which kills its refresh token too.
     *
     * @return true if the token was valid and its session is now revoked
     */
    public boolean revokeSession(String accessToken) {
        removeExpiredRevocations();
        Instant sessionEnd = Instant.now().plus(properties.refreshTtl());
        return parseActive(accessToken, ACCESS)
                .map(claims -> revokedSessions.putIfAbsent(claims.get(SESSION_CLAIM, String.class), sessionEnd) == null)
                .orElse(false);
    }

    private Optional<Claims> parseActive(String token, String expectedType) {
        return parse(token)
                .filter(claims -> expectedType.equals(claims.get(TYPE_CLAIM, String.class)))
                .filter(claims -> !revokedSessions.containsKey(claims.get(SESSION_CLAIM, String.class)));
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(parser.parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            // bad signature, malformed or expired
            return Optional.empty();
        }
    }

    // sessions whose refresh token has expired can't be used anyway, no need to remember them
    private void removeExpiredRevocations() {
        Instant now = Instant.now();
        revokedSessions.values().removeIf(sessionEnd -> sessionEnd.isBefore(now));
    }

}
