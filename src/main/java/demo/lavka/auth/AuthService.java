package demo.lavka.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AuthService {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    // username -> password hash
    private final Map<String, String> users = new ConcurrentHashMap<>();
    // token -> username
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public void register(String username, String password) {
        String hash = passwordEncoder.encode(password);
        if (users.putIfAbsent(username, hash) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }
    }

    public String login(String username, String password) {
        String hash = users.get(username);
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        String token = newToken();
        tokens.put(token, username);
        return token;
    }

    public void logout(String token) {
        if (tokens.remove(token) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
