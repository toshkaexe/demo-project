package demo.lavka.controller;

import demo.lavka.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API spec is reconnaissance for an attacker: it is served only where springdoc is switched on
 * (the local profile), and nowhere by default.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void specIsHiddenByDefault() throws Exception {
        // the test profile doesn't include local, like any profile other than local
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/openapi.yaml")).andExpect(status().isNotFound());
    }

    @Nested
    @TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    class WhenSwitchedOnLikeInTheLocalProfile {

        @Test
        void specAndSwaggerUiAreServed() throws Exception {
            mvc.perform(get("/openapi.yaml"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("openapi: 3.1")));
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }

    }

}
