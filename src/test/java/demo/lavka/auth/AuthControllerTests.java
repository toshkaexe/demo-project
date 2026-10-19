package demo.lavka.auth;

import java.util.Base64;

import demo.lavka.TestcontainersConfiguration;
import demo.lavka.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthControllerTests {

    private static final String CREDENTIALS = """
            {"email": "anton@example.com", "password": "secret123"}
            """;

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

    @Test
    void registerReturnsUsableToken() throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"access_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void registerLoginLogout() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());

        MvcResult login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        String token = login.getResponse().getContentAsString().replaceAll(".*\"access_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // token is revoked, second logout fails
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerTwiceReturnsConflict() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"anton@example.com\", \"password\": \"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerWithInvalidBodyReturnsBadRequest() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"\", \"password\": \"1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithInvalidEmailReturnsBadRequest() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-email\", \"password\": \"secret123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"Anton@Example.com\", \"password\": \"secret123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ANTON@example.com\", \"password\": \"secret123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void newUserIsPending() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        String status = jdbc.queryForObject("select status from users where email = 'anton@example.com'", String.class);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void blockedUserCannotLogin() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        jdbc.update("update users set status = 'BLOCKED' where email = 'anton@example.com'");
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletedUserCannotLogin() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        jdbc.update("update users set status = 'DELETED' where email = 'anton@example.com'");
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsJwtWithUserIdAndEmail() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"access_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        Long userId = userRepository.findByEmail("anton@example.com").orElseThrow().getId();
        assertThat(payload).contains("\"sub\":\"" + userId + "\"", "\"email\":\"anton@example.com\"", "\"typ\":\"access\"", "\"exp\":");
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = body.replaceAll(".*\"refresh_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithForgedTokenReturnsUnauthorized() throws Exception {
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutTokenReturnsUnauthorized() throws Exception {
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

}
