package demo.lavka.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

import demo.lavka.config.JwtConfig;
import demo.lavka.model.TokenPair;
import demo.lavka.auth.InvalidTokenException.Reason;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
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
                build(userId, TokenType.ACCESS, now, now.plus(properties.accessTtl())),
                build(userId, TokenType.REFRESH, now, now.plus(properties.refreshTtl())));
    }

    /**
     * The single place where a token is checked. Steps, in order:
     * <ol>
     *     <li>signature matches our key, the token is well-formed and signed (jjwt);</li>
     *     <li>{@code exp} is not in the past (jjwt);</li>
     *     <li>{@code typ} is the expected one: access vs refresh;</li>
     *     <li>the token was not revoked by logout or refresh.</li>
     * </ol>
     *
     * @return data from a token that passed all checks
     * @throws InvalidTokenException with the reason of the first failed check
     */
    public VerifiedToken validateToken(String token, TokenType expectedType) {
        Claims claims = parseSigned(token);

        if (!expectedType.claimValue().equals(claims.get(TYPE_CLAIM, String.class))) {
            throw new InvalidTokenException(Reason.WRONG_TYPE);
        }
        if (revoked.containsKey(claims.getId())) {
            throw new InvalidTokenException(Reason.REVOKED);
        }
        return new VerifiedToken(claims.getId(), Long.valueOf(claims.getSubject()), claims.getExpiration().toInstant());
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

    private String build(Long userId, TokenType type, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(TYPE_CLAIM, type.claimValue())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /**
     * jjwt recomputes the HMAC of {@code header.payload} with our key and compares it with the token's
     * signature, then checks {@code exp}. Unsigned tokens are rejected.
     */
    private Claims parseSigned(String token) {
        try {
            return parser.parseSignedClaims(token).getPayload();
        } catch (SignatureException e) {
            throw new InvalidTokenException(Reason.BAD_SIGNATURE);
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(Reason.EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(Reason.MALFORMED);
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
