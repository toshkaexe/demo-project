package demo.lavka.controller;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.TestcontainersConfiguration;
import demo.lavka.repository.ProductRepository;
import demo.lavka.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    // writes need the warehouse role
    private String warehouse;
    private String customer;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        warehouse = TestUsers.warehouse(mvc, jdbc, "warehouse@example.com");
        customer = TestUsers.customer(mvc, "customer@example.com");
    }

    @AfterEach
    void cleanUp() {
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- access ---

    @Test
    void readingIsOpenForEveryone() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        mvc.perform(get("/products")).andExpect(status().isOk());
        mvc.perform(get("/products/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void writesWithoutTokenReturn401() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(json("Bread", 1, "1.00")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/products/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json("Hacked", 0, "0.00")))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/products/{id}", id)).andExpect(status().isUnauthorized());
        // the role is checked before the body: no validation details for an anonymous client
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(productRepository.findById(id).orElseThrow().getName()).isEqualTo("Milk");
    }

    @Test
    void customerCannotChangeCatalog() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        mvc.perform(post("/products").header("Authorization", TestUsers.bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Bread", 1, "1.00")))
                .andExpect(status().isForbidden())
                .andExpect(status().reason("Only WAREHOUSE can do this"));
        mvc.perform(put("/products/{id}", id).header("Authorization", TestUsers.bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Hacked", 0, "0.00")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/products/{id}", id).header("Authorization", TestUsers.bearer(customer)))
                .andExpect(status().isForbidden());

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(id).orElseThrow().getName()).isEqualTo("Milk");
    }

    // --- create ---

    @Test
    void createReturnsCreatedProductWithLocation() throws Exception {
        create(json("Milk", 10, "89.90"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/products/" + onlyProductId())))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Milk"))
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.price").value(89.90));

        var saved = productRepository.findAll().getFirst();
        assertThat(saved.getPrice()).isEqualByComparingTo(new BigDecimal("89.90"));
    }

    @Test
    void createValidatesBody() throws Exception {
        create(json(" ", 1, "1.00")).andExpect(status().isBadRequest());
        create(json("Milk", -1, "1.00")).andExpect(status().isBadRequest());
        create(json("Milk", 1, "-0.01")).andExpect(status().isBadRequest());
        create(json("Milk", 1, "1.999")).andExpect(status().isBadRequest());
        create("{\"name\":\"Milk\",\"price\":1.00}").andExpect(status().isBadRequest());
        create("{\"name\":\"Milk\",\"quantity\":1}").andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }

    // --- read ---

    @Test
    void getReturnsProductOr404() throws Exception {
        Integer id = createAndGetId(json("Bread", 5, "45.00"));

        mvc.perform(get("/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bread"));
        mvc.perform(get("/products/{id}", id + 1000)).andExpect(status().isNotFound());
        mvc.perform(get("/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    // --- update ---

    @Test
    void updateReplacesAllFields() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        update(id, json("Milk 3.2%", 0, "99.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Milk 3.2%"))
                .andExpect(jsonPath("$.quantity").value(0));

        var saved = productRepository.findById(id).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Milk 3.2%");
        assertThat(saved.getQuantity()).isZero();
        assertThat(saved.getPrice()).isEqualByComparingTo("99.00");
    }

    @Test
    void updateUnknownProductReturns404() throws Exception {
        update(12345, json("Milk", 1, "1.00")).andExpect(status().isNotFound());
    }

    @Test
    void updateValidatesBodyAndKeepsOldValues() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        update(id, json("Milk", -5, "89.90")).andExpect(status().isBadRequest());

        assertThat(productRepository.findById(id).orElseThrow().getQuantity()).isEqualTo(10);
    }

    // --- delete ---

    @Test
    void deleteRemovesProduct() throws Exception {
        Integer id = createAndGetId(json("Milk", 10, "89.90"));

        mvc.perform(delete("/products/{id}", id).header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(status().isNoContent());

        assertThat(productRepository.existsById(id)).isFalse();
        mvc.perform(delete("/products/{id}", id).header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/products").header("Authorization", TestUsers.bearer(warehouse))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(Integer id, String body) throws Exception {
        return mvc.perform(put("/products/{id}", id).header("Authorization", TestUsers.bearer(warehouse))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Integer createAndGetId(String body) throws Exception {
        String response = create(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private Integer onlyProductId() {
        return productRepository.findAll().getFirst().getId();
    }

    private static String json(String name, int quantity, String price) {
        return """
                {"name": "%s", "quantity": %d, "price": %s}
                """.formatted(name, quantity, price);
    }

}
