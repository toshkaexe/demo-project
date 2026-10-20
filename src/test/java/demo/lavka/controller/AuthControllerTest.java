package demo.lavka.controller;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.TestcontainersConfiguration;
import demo.lavka.model.TokenPair;
import demo.lavka.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerTest {

    private static final String EMAIL = "anton@example.com";
    private static final String PASSWORD = "secret123";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // --- register ---

    @Test
    void registerReturnsTokenPairAndStoresPasswordHash() throws Exception {
        register(EMAIL, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty());

        var row = jdbc.queryForMap("select * from users where email = ?", EMAIL);
        assertThat((String) row.get("password_hash")).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void registerTwiceWithSameEmailInAnyCaseReturnsConflict() throws Exception {
        register(EMAIL, PASSWORD).andExpect(status().isCreated());
        register("Anton@Example.COM", PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void registerValidatesBody() throws Exception {
        register("not-an-email", PASSWORD).andExpect(status().isBadRequest());
        register(EMAIL, "12345").andExpect(status().isBadRequest());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + EMAIL + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- login ---

    @Test
    void loginReturnsTokenPair() throws Exception {
        register(EMAIL, PASSWORD);

        login("ANTON@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailReturnsUnauthorized() throws Exception {
        register(EMAIL, PASSWORD);

        login(EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        login("nobody@example.com", PASSWORD).andExpect(status().isUnauthorized());
    }

    // --- GET /users/me ---

    @Test
    void meReturnsCurrentUser() throws Exception {
        TokenPair tokens = tokens(register(EMAIL, PASSWORD));
        Long id = userRepository.findByEmail(EMAIL).orElseThrow().getId();

        me(tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.created_at").isNotEmpty())
                .andExpect(jsonPath("$.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.password_hash").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void meRequiresValidAccessToken() throws Exception {
        TokenPair tokens = tokens(register(EMAIL, PASSWORD));

        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        me("garbage").andExpect(status().isUnauthorized());
        me(tokens.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsUnauthorizedIfUserWasDeleted() throws Exception {
        TokenPair tokens = tokens(register(EMAIL, PASSWORD));
        userRepository.deleteAll();

        me(tokens.accessToken()).andExpect(status().isUnauthorized());
    }

    // --- refresh ---

    @Test
    void refreshReturnsNewWorkingPair() throws Exception {
        TokenPair first = tokens(register(EMAIL, PASSWORD));

        TokenPair second = tokens(refresh(first.refreshToken()).andExpect(status().isOk()));

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        me(second.accessToken()).andExpect(status().isOk());
    }

    @Test
    void refreshTokenCanBeUsedOnlyOnce() throws Exception {
        TokenPair first = tokens(register(EMAIL, PASSWORD));

        refresh(first.refreshToken()).andExpect(status().isOk());
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsAccessTokenAndGarbage() throws Exception {
        TokenPair tokens = tokens(register(EMAIL, PASSWORD));

        refresh(tokens.accessToken()).andExpect(status().isUnauthorized());
        refresh("garbage").andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --- logout ---

    @Test
    void logoutRevokesBothTokens() throws Exception {
        TokenPair tokens = tokens(register(EMAIL, PASSWORD));

        logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isNoContent());

        me(tokens.accessToken()).andExpect(status().isUnauthorized());
        refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
        logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDoesNotTouchOtherLogins() throws Exception {
        TokenPair phone = tokens(register(EMAIL, PASSWORD));
        TokenPair laptop = tokens(login(EMAIL, PASSWORD));

        logout(phone.accessToken(), phone.refreshToken()).andExpect(status().isNoContent());

        me(laptop.accessToken()).andExpect(status().isOk());
    }

    @Test
    void logoutRejectsMissingTokenOrSomeoneElsesRefreshToken() throws Exception {
        TokenPair anton = tokens(register(EMAIL, PASSWORD));
        TokenPair other = tokens(register("other@example.com", PASSWORD));

        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(anton.refreshToken())))
                .andExpect(status().isUnauthorized());
        logout(anton.accessToken(), other.refreshToken()).andExpect(status().isUnauthorized());

        // nothing was revoked by the failed attempts
        me(anton.accessToken()).andExpect(status().isOk());
        me(other.accessToken()).andExpect(status().isOk());
    }

    // --- helpers ---

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password)));
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/users/me").header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        return mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(refreshBody(refreshToken)));
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refresh_token\": \"" + refreshToken + "\"}";
    }

    private static TokenPair tokens(ResultActions result) throws Exception {
        String body = result.andExpect(jsonPath("$.access_token", not(nullValue())))
                .andReturn().getResponse().getContentAsString();
        return new TokenPair(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
    }

}
