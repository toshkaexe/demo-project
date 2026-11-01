package demo.lavka.controller;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.TestcontainersConfiguration;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.Products;
import demo.lavka.repository.OrderRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String token;
    private Integer milk;
    private Integer bread;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        token = register("anton@example.com");
        milk = product("Milk", 10, "89.90");
        bread = product("Bread", 5, "45.00");
    }

    // other test classes delete users and products, and orders would block that
    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
        productRepository.deleteAll();
    }

    // --- auth ---

    @Test
    void everyEndpointNeedsToken() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/1/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(items(milk, 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/orders/1/status").contentType(MediaType.APPLICATION_JSON).content(statusBody("CANCELLED")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    // --- create ---

    @Test
    void createSavesItemsWithPriceAndTakesThemFromStock() throws Exception {
        Long userId = userRepository.findByEmail("anton@example.com").orElseThrow().getId();

        create(token, items(milk, 2, bread, 1))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/orders/" + orderRepository.findAll().getFirst().getId())))
                .andExpect(jsonPath("$.user_id").value(userId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].product_id").value(milk))
                .andExpect(jsonPath("$.items[0].name").value("Milk"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].price").value(89.90))
                .andExpect(jsonPath("$.items[0].amount").value(179.80))
                .andExpect(jsonPath("$.total").value(224.80))
                .andExpect(jsonPath("$.created_at").isNotEmpty())
                .andExpect(jsonPath("$.completed_at").doesNotExist());

        assertThat(stock(milk)).isEqualTo(8);
        assertThat(stock(bread)).isEqualTo(4);
    }

    @Test
    void laterPriceChangeDoesNotChangeOldOrder() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));
        jdbc.update("update products set price = 150 where id = ?", milk);

        getOrder(token, id).andExpect(jsonPath("$.items[0].price").value(89.90));
    }

    @Test
    void notEnoughStockRejectsWholeOrder() throws Exception {
        create(token, items(bread, 1, milk, 11))
                .andExpect(status().isConflict());

        // bread was fine, but nothing is taken: the order is all or nothing
        assertThat(stock(bread)).isEqualTo(5);
        assertThat(stock(milk)).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void createValidatesBody() throws Exception {
        create(token, "{\"items\": []}").andExpect(status().isBadRequest());
        create(token, "{}").andExpect(status().isBadRequest());
        create(token, items(milk, 0)).andExpect(status().isBadRequest());
        create(token, items(milk, 1, milk, 2)).andExpect(status().isBadRequest());   // same product twice
        create(token, items(milk + bread + 1000, 1)).andExpect(status().isBadRequest()); // unknown product
        assertThat(orderRepository.count()).isZero();
        assertThat(stock(milk)).isEqualTo(10);
    }

    // --- read ---

    @Test
    void listReturnsOnlyOwnOrdersNewestFirst() throws Exception {
        Long first = createAndGetId(token, items(milk, 1));
        Long second = createAndGetId(token, items(bread, 1));
        createAndGetId(register("other@example.com"), items(milk, 1));

        mvc.perform(get("/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].items[0].name").value("Bread"))
                .andExpect(jsonPath("$[1].id").value(first));
    }

    @Test
    void orderOfAnotherUserIsNotFound() throws Exception {
        Long othersOrder = createAndGetId(register("other@example.com"), items(milk, 1));

        getOrder(token, othersOrder).andExpect(status().isNotFound());
        mvc.perform(get("/orders/{id}/history", othersOrder).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        changeStatus(token, othersOrder, "CANCELLED").andExpect(status().isNotFound());
        assertThat(orderRepository.findById(othersOrder).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        getOrder(token, 12345L).andExpect(status().isNotFound());
    }

    // --- status ---

    @Test
    void orderGoesThroughAllStatusesToCompleted() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));

        changeStatus(token, id, "PROCESSING").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        changeStatus(token, id, "READY").andExpect(status().isOk());
        changeStatus(token, id, "COMPLETED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completed_at").isNotEmpty());

        // read back from the database: the change must be saved, not only returned
        var saved = orderRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(stock(milk)).isEqualTo(9);
    }

    @Test
    void historyHasEveryStatusChange() throws Exception {
        Long userId = userRepository.findByEmail("anton@example.com").orElseThrow().getId();
        Long id = createAndGetId(token, items(milk, 1));
        changeStatus(token, id, "PROCESSING").andExpect(status().isOk());
        changeStatus(token, id, "CANCELLED").andExpect(status().isOk());

        mvc.perform(get("/orders/{id}/history", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].from_status").doesNotExist())
                .andExpect(jsonPath("$[0].to_status").value("CREATED"))
                .andExpect(jsonPath("$[1].from_status").value("CREATED"))
                .andExpect(jsonPath("$[1].to_status").value("PROCESSING"))
                .andExpect(jsonPath("$[2].from_status").value("PROCESSING"))
                .andExpect(jsonPath("$[2].to_status").value("CANCELLED"))
                .andExpect(jsonPath("$[2].changed_by").value(userId))
                .andExpect(jsonPath("$[2].changed_at").isNotEmpty());
    }

    @Test
    void cancelReturnsItemsToStock() throws Exception {
        Long id = createAndGetId(token, items(milk, 3, bread, 2));
        changeStatus(token, id, "PROCESSING").andExpect(status().isOk());

        changeStatus(token, id, "CANCELLED").andExpect(status().isOk()).andExpect(jsonPath("$.completed_at").doesNotExist());

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(milk)).isEqualTo(10);
        assertThat(stock(bread)).isEqualTo(5);
    }

    @Test
    void wrongTransitionsAreRejected() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));

        changeStatus(token, id, "COMPLETED").andExpect(status().isConflict());  // skipping steps
        changeStatus(token, id, "CREATED").andExpect(status().isConflict());    // same status
        changeStatus(token, id, "CANCELLED").andExpect(status().isOk());
        changeStatus(token, id, "PROCESSING").andExpect(status().isConflict()); // CANCELLED is final
        changeStatus(token, id, "CANCELLED").andExpect(status().isConflict());  // stock is not returned twice

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(milk)).isEqualTo(10);
    }

    @Test
    void changeStatusValidatesBody() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));

        changeStatus(token, id, "LOST").andExpect(status().isBadRequest());
        mvc.perform(patch("/orders/{id}/status", id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --- products in orders ---

    @Test
    void orderedProductCannotBeDeleted() throws Exception {
        createAndGetId(token, items(milk, 1));

        mvc.perform(delete("/products/{id}", milk)).andExpect(status().isConflict());
        assertThat(productRepository.existsById(milk)).isTrue();
    }

    // --- helpers ---

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "secret123"}
                        """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private Integer product(String name, int quantity, String price) {
        return productRepository.save(Products.builder().name(name).quantity(quantity).price(new BigDecimal(price)).build())
                .getId();
    }

    private int stock(Integer productId) {
        return jdbc.queryForObject("select quantity from products where id = ?", Integer.class, productId);
    }

    private ResultActions create(String accessToken, String body) throws Exception {
        return mvc.perform(post("/orders").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Long createAndGetId(String accessToken, String body) throws Exception {
        String response = create(accessToken, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private ResultActions getOrder(String accessToken, Long id) throws Exception {
        return mvc.perform(get("/orders/{id}", id).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions changeStatus(String accessToken, Long id, String status) throws Exception {
        return mvc.perform(patch("/orders/{id}/status", id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(statusBody(status)));
    }

    // items(productId, quantity, productId, quantity, ...)
    private static String items(Object... pairs) {
        StringBuilder json = new StringBuilder("{\"items\": [");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("{\"product_id\": ").append(pairs[i]).append(", \"quantity\": ").append(pairs[i + 1]).append('}');
        }
        return json.append("]}").toString();
    }

    private static String statusBody(String status) {
        return "{\"status\": \"" + status + "\"}";
    }

}
