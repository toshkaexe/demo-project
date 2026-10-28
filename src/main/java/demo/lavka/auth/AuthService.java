package demo.lavka.auth;

import java.util.Locale;

import demo.lavka.auth.JWTService.VerifiedToken;
import demo.lavka.entity.User;
import demo.lavka.model.TokenPair;
import demo.lavka.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JWTService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JWTService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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

    public TokenPair login(String email, String password) {
        User user = userRepository.findByEmail(normalize(email))
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> unauthorized("Invalid email or password"));
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
