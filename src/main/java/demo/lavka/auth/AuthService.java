package demo.lavka.auth;

import java.util.Locale;

import demo.lavka.entity.User;
import demo.lavka.entity.UserStatus;
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

    @Transactional
    /**
     * Creates the user and logs them in right away.
     *
     * @return access and refresh tokens for the new user
     */
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
        return jwtService.issueTokens(user.getId(), user.getEmail());
    }

    public TokenPair login(String email, String password) {
        User user = userRepository.findByEmail(normalize(email))
                // deleted users are treated as non-existent
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (user.getStatus() == UserStatus.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is blocked");
        }
        return jwtService.issueTokens(user.getId(), user.getEmail());
    }

    public void logout(String token) {
        if (!jwtService.revokeSession(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    // emails are case-insensitive: Anton@Mail.com and anton@mail.com are the same user
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException userAlreadyExists() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
    }

}
