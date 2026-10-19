package demo.lavka.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import demo.lavka.entity.User;
import demo.lavka.entity.UserStatus;
import demo.lavka.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AuthService {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    private final UserRepository userRepository;

    // token -> user id; tokens live only in memory, a restart logs everyone out
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void register(String email, String password) {
        String normalized = normalize(email);
        if (userRepository.existsByEmail(normalized)) {
            throw userAlreadyExists();
        }
        try {
            userRepository.saveAndFlush(new User(normalized, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // concurrent registration with the same email hit the unique constraint
            throw userAlreadyExists();
        }
    }

    public String login(String email, String password) {
        User user = userRepository.findByEmail(normalize(email))
                // deleted users are treated as non-existent
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (user.getStatus() == UserStatus.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is blocked");
        }
        String token = newToken();
        tokens.put(token, user.getId());
        return token;
    }

    public void logout(String token) {
        if (tokens.remove(token) == null) {
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

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
