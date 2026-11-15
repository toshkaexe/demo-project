package demo.lavka.auth;

import java.util.Locale;
import java.util.UUID;

import demo.lavka.auth.JWTService.VerifiedToken;
import demo.lavka.entity.Role;
import demo.lavka.entity.User;
import demo.lavka.model.TokenPair;
import demo.lavka.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    // the same 403 text everywhere: no role names in responses, the log has the details
    public static final String ACCESS_DENIED = "Access denied";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JWTService jwtService;
    // checked against when the email is unknown, so that login spends the same BCrypt time either way
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JWTService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Creates the user and logs them in right away.
     */
    @Transactional
    public TokenPair register(String email, String password) {
        String normalized = normalize(email);
        if (userRepository.existsByEmail(normalized)) {
            throw userAlreadyExists();
        }
        User user;
        try {
            user = userRepository.saveAndFlush(new User(normalized, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // concurrent registration with the same email hit the unique constraint
            throw userAlreadyExists();
        }
        return jwtService.issueTokens(user.getId());
    }

    /**
     * An unknown email and a wrong password look the same to the client: the same error and the same time.
     * Without the dummy check an unknown email answers without BCrypt, tens of milliseconds faster,
     * and the response time tells which emails are registered.
     * <p>
     * The email is never logged: it is personal data, and the client sends any string here (no {@code @Email}),
     * so a line break in it would forge log lines. The user id is enough to find the account.
     */
    public TokenPair login(String email, String password) {
        String normalized = normalize(email);
        User user = userRepository.findByEmail(normalized).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(password,
                user != null ? user.getPasswordHash() : dummyPasswordHash);
        if (user == null) {
            log.warn("Login failed: unknown email");
            throw unauthorized("Invalid email or password");
        }
        if (!passwordMatches) {
            log.warn("Login failed: wrong password for userId={}", user.getId());
            throw unauthorized("Invalid email or password");
        }
        log.info("Login succeeded: userId={}", user.getId());
        return jwtService.issueTokens(user.getId());
    }

    /**
     * Revokes both tokens of the pair. The access token may already be expired (it lives only 15 minutes),
     * otherwise a user who logs out later would get 401 and keep a live refresh token.
     */
    public void logout(String accessToken, String refreshToken) {
        VerifiedToken refresh = jwtService.validateToken(refreshToken, TokenType.REFRESH);
        VerifiedToken access = jwtService.validateTokenIgnoringExpiry(accessToken, TokenType.ACCESS);
        if (!refresh.userId().equals(access.userId())) {
            throw unauthorized("Refresh token belongs to another user");
        }
        // the refresh token matters most: it can mint new pairs for 30 days
        jwtService.revoke(refresh);
        jwtService.revoke(access);
    }

    /**
     * Exchanges a refresh token for a new pair. The old refresh token can't be used again.
     */
    public TokenPair refresh(String refreshToken) {
        VerifiedToken refresh = jwtService.validateToken(refreshToken, TokenType.REFRESH);
        // revoke first: if two requests race with the same token, only one gets a new pair
        if (!jwtService.revoke(refresh)) {
            throw new InvalidTokenException(InvalidTokenException.Reason.REVOKED);
        }
        if (!userRepository.existsById(refresh.userId())) {
            throw unauthorized("User not found");
        }
        return jwtService.issueTokens(refresh.userId());
    }

    /**
     * @return the user the access token belongs to
     */
    public User authenticate(String accessToken) {
        VerifiedToken access = jwtService.validateToken(accessToken, TokenType.ACCESS);
        return userRepository.findById(access.userId())
                .orElseThrow(() -> unauthorized("User not found"));
    }

    /**
     * Like {@link #authenticate}, and the user must have the given role.
     *
     * @throws ResponseStatusException 403 if the role is different. The client gets a neutral "Access denied":
     *         role names are internals, not something to show to whoever probes the API. Details are in the log.
     */
    public User requireRole(String accessToken, Role role) {
        User user = authenticate(accessToken);
        if (user.getRole() != role) {
            log.warn("Access denied: userId={} role={} needs {}", user.getId(), user.getRole(), role);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ACCESS_DENIED);
        }
        return user;
    }

    // emails are case-insensitive: Anton@Mail.com and anton@mail.com are the same user
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException userAlreadyExists() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

}
