package demo.lavka.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

import demo.lavka.config.JwtConfig;
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
 * Every login returns a pair: a short-lived access token and a long-lived refresh token,
 * told apart by the {@code typ} claim. JWTs are stateless, so revoked tokens (logout,
 * a refresh token that was already exchanged) are kept on an in-memory denylist by their
 * id ({@code jti}) until they expire. A restart clears the denylist.
 */
@Service
@EnableConfigurationProperties(JwtConfig.class)
public class JWTService {

    private static final String TYPE_CLAIM = "typ";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final JwtConfig properties;
    private final JwtParser parser;

    // revoked token id (jti) -> when the token expires
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public JWTService(JwtConfig properties) {
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

    public TokenPair issueTokens(Long userId) {
        Instant now = Instant.now();
        return new TokenPair(
                build(userId, ACCESS, now, now.plus(properties.accessTtl())),
                build(userId, REFRESH, now, now.plus(properties.refreshTtl())));
    }

    /**
     * @return the token if it is a valid, unexpired, not revoked access token
     */
    public Optional<VerifiedToken> verifyAccess(String token) {
        return verify(token, ACCESS);
    }

    /**
     * @return the token if it is a valid, unexpired, not revoked refresh token
     */
    public Optional<VerifiedToken> verifyRefresh(String token) {
        return verify(token, REFRESH);
    }

    /**
     * Makes the token unusable until it expires.
     *
     * @return false if the token was already revoked
     */
    public boolean revoke(VerifiedToken token) {
        removeExpiredRevocations();
        return revoked.putIfAbsent(token.id(), token.expiresAt()) == null;
    }

    private String build(Long userId, String type, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(TYPE_CLAIM, type)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    private Optional<VerifiedToken> verify(String token, String expectedType) {
        return parse(token)
                .filter(claims -> expectedType.equals(claims.get(TYPE_CLAIM, String.class)))
                .filter(claims -> !revoked.containsKey(claims.getId()))
                .map(claims -> new VerifiedToken(
                        claims.getId(), Long.valueOf(claims.getSubject()), claims.getExpiration().toInstant()));
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(parser.parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            // bad signature, malformed or expired
            return Optional.empty();
        }
    }

    // expired tokens are rejected anyway, no need to remember them
    private void removeExpiredRevocations() {
        Instant now = Instant.now();
        revoked.values().removeIf(expiresAt -> expiresAt.isBefore(now));
    }

    public record VerifiedToken(String id, Long userId, Instant expiresAt) {
    }

}
