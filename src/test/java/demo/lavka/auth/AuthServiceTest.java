package demo.lavka.auth;

import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import demo.lavka.entity.User;
import demo.lavka.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthService.login: не выдаёт, какие email зарегистрированы, и не пишет email в лог")
class AuthServiceTest {

    private static final String PASSWORD = "secret123";

    private final UserRepository userRepository = mock(UserRepository.class);
    // cost 4 instead of 10: the test checks that BCrypt runs, not how slow it is
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final AuthService authService = new AuthService(userRepository, passwordEncoder, mock(JWTService.class));

    private final Logger authLog = (Logger) LoggerFactory.getLogger(AuthService.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        authLog.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        authLog.detachAppender(logged);
    }

    @Test
    @DisplayName("Неизвестный email тоже проверяется BCrypt: время ответа не выдаёт, есть ли пользователь")
    void unknownEmailStillRunsBcrypt() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nobody@example.com", PASSWORD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid email or password");
        verify(passwordEncoder, times(1)).matches(eq(PASSWORD), anyString());
    }

    @Test
    @DisplayName("Неизвестный email и неверный пароль дают одинаковую ошибку")
    void unknownEmailAndWrongPasswordLookTheSame() {
        User user = new User("anton@example.com", passwordEncoder.encode(PASSWORD));
        when(userRepository.findByEmail("anton@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        String wrongPassword = errorOf(() -> authService.login("anton@example.com", "wrong-password"));
        String unknownEmail = errorOf(() -> authService.login("nobody@example.com", PASSWORD));

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("Email с переводом строки не попадает в лог и не подделывает строки")
    void emailIsNotLogged() {
        String forged = "x@example.com\n2026-01-01 INFO Login succeeded: userId=1";
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(forged, PASSWORD)).isInstanceOf(ResponseStatusException.class);

        List<String> lines = logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).containsExactly("Login failed: unknown email");
    }

    private static String errorOf(Runnable call) {
        try {
            call.run();
        } catch (ResponseStatusException e) {
            return e.getStatusCode() + " " + e.getReason();
        }
        throw new AssertionError("login must fail");
    }

}
