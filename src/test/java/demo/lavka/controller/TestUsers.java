package demo.lavka.controller;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.entity.Role;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers users through the API and gives them a role the way it is done for real: with UPDATE in the database.
 * The role is read from the database on every request, so the token from registration already works with the new role.
 */
final class TestUsers {

    private TestUsers() {
    }

    /**
     * @return access token of a new {@link Role#CUSTOMER}
     */
    static String customer(MockMvc mvc, String email) throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "secret123"}
                        """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    /**
     * @return access token of a new {@link Role#WAREHOUSE} employee
     */
    static String warehouse(MockMvc mvc, JdbcTemplate jdbc, String email) throws Exception {
        String token = customer(mvc, email);
        jdbc.update("update users set role = 'WAREHOUSE' where email = ?", email);
        return token;
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

}
