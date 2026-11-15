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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    // a customer: places and cancels own orders
    private String token;
    // a warehouse employee: moves orders through the statuses
    private String warehouse;
    private Integer milk;
    private Integer bread;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        token = register("anton@example.com");
        warehouse = TestUsers.warehouse(mvc, jdbc, "warehouse@example.com");
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

    // --- delivery address ---

    @Test
    void orderGoesToActiveAddressByDefault() throws Exception {
        create(token, items(milk, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery_address.city").value("Москва"))
                .andExpect(jsonPath("$.delivery_address.street").value("Тверская"))
                .andExpect(jsonPath("$.delivery_address.recipient_name").value("Антон Иванов"));
    }

    @Test
    void orderGoesToChosenAddress() throws Exception {
        Long dacha = TestUsers.address(mvc, token, "Солнечногорск");

        create(token, withAddress(items(milk, 1), dacha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery_address.city").value("Солнечногорск"));
    }

    @Test
    void addressOfAnotherUserOrNoAddressIsRejected() throws Exception {
        String other = TestUsers.customer(mvc, "other@example.com");
        Long othersAddress = TestUsers.address(mvc, other, "Казань");

        create(token, withAddress(items(milk, 1), othersAddress))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Address " + othersAddress + " not found"));

        String homeless = TestUsers.customer(mvc, "homeless@example.com");
        create(homeless, items(milk, 1))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("No delivery address: add one with POST /users/me/addresses"));

        // nothing was taken from stock
        assertThat(stock(milk)).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void orderKeepsAddressAfterItIsChangedOrDeleted() throws Exception {
        Long home = jdbc.queryForObject("select id from user_addresses where city = 'Москва'", Long.class);
        Long orderId = createAndGetId(token, items(milk, 1));

        mvc.perform(put("/users/me/addresses/{id}", home).header("Authorization", TestUsers.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"postal_code": "190000", "country": "Россия", "city": "Санкт-Петербург",
                                 "street": "Невский", "house": "1", "recipient_name": "Антон Иванов"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/orders/{id}", orderId).header("Authorization", TestUsers.bearer(token)))
                .andExpect(jsonPath("$.delivery_address.city").value("Москва"));

        mvc.perform(delete("/users/me/addresses/{id}", home).header("Authorization", TestUsers.bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/orders/{id}", orderId).header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(jsonPath("$.delivery_address.city").value("Москва"));
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
                .andExpect(jsonPath("$.user_email").value("anton@example.com"))
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

        changeStatus(warehouse, id, "PROCESSING").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        changeStatus(warehouse, id, "READY").andExpect(status().isOk());
        changeStatus(warehouse, id, "COMPLETED")
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
        Long warehouseId = userRepository.findByEmail("warehouse@example.com").orElseThrow().getId();
        Long id = createAndGetId(token, items(milk, 1));
        changeStatus(warehouse, id, "PROCESSING").andExpect(status().isOk());
        changeStatus(warehouse, id, "READY").andExpect(status().isOk());
        changeStatus(warehouse, id, "COMPLETED").andExpect(status().isOk());

        mvc.perform(get("/orders/{id}/history", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].from_status").doesNotExist())
                .andExpect(jsonPath("$[0].to_status").value("CREATED"))
                .andExpect(jsonPath("$[0].changed_by").value(userId))
                .andExpect(jsonPath("$[1].from_status").value("CREATED"))
                .andExpect(jsonPath("$[1].to_status").value("PROCESSING"))
                .andExpect(jsonPath("$[1].changed_by").value(warehouseId))
                .andExpect(jsonPath("$[2].from_status").value("PROCESSING"))
                .andExpect(jsonPath("$[2].to_status").value("READY"))
                .andExpect(jsonPath("$[3].from_status").value("READY"))
                .andExpect(jsonPath("$[3].to_status").value("COMPLETED"))
                .andExpect(jsonPath("$[3].changed_by").value(warehouseId))
                .andExpect(jsonPath("$[3].changed_at").isNotEmpty());
    }

    @Test
    void cancelReturnsItemsToStock() throws Exception {
        Long userId = userRepository.findByEmail("anton@example.com").orElseThrow().getId();
        Long id = createAndGetId(token, items(milk, 3, bread, 2));
        assertThat(stock(milk)).isEqualTo(7);

        changeStatus(token, id, "CANCELLED").andExpect(status().isOk()).andExpect(jsonPath("$.completed_at").doesNotExist());

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(milk)).isEqualTo(10);
        assertThat(stock(bread)).isEqualTo(5);
        mvc.perform(get("/orders/{id}/history", id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].from_status").value("CREATED"))
                .andExpect(jsonPath("$[1].to_status").value("CANCELLED"))
                .andExpect(jsonPath("$[1].changed_by").value(userId));
    }

    @Test
    void wrongTransitionsAreRejected() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));

        changeStatus(warehouse, id, "COMPLETED").andExpect(status().isConflict());  // skipping steps
        changeStatus(warehouse, id, "CREATED").andExpect(status().isConflict());    // same status
        changeStatus(warehouse, id, "CANCELLED").andExpect(status().isOk());
        changeStatus(warehouse, id, "PROCESSING").andExpect(status().isConflict()); // CANCELLED is final
        changeStatus(warehouse, id, "CANCELLED").andExpect(status().isConflict());  // stock is not returned twice

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(milk)).isEqualTo(10);
    }

    @Test
    void orderCannotBeCancelledAfterAssemblingStarted() throws Exception {
        Long id = createAndGetId(token, items(milk, 2));

        changeStatus(warehouse, id, "PROCESSING").andExpect(status().isOk());
        changeStatus(token, id, "CANCELLED").andExpect(status().isConflict())
                .andExpect(status().reason("Can't change order status from PROCESSING to CANCELLED"));
        changeStatus(warehouse, id, "CANCELLED").andExpect(status().isConflict());  // the warehouse can't either

        changeStatus(warehouse, id, "READY").andExpect(status().isOk());
        changeStatus(token, id, "CANCELLED").andExpect(status().isConflict());
        changeStatus(warehouse, id, "CANCELLED").andExpect(status().isConflict());

        changeStatus(warehouse, id, "COMPLETED").andExpect(status().isOk());
        changeStatus(warehouse, id, "CREATED").andExpect(status().isConflict());    // COMPLETED is final

        // nothing came back to stock: the customer got the milk
        assertThat(stock(milk)).isEqualTo(8);
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void customerCanOnlyCancel() throws Exception {
        Long id = createAndGetId(token, items(milk, 1));

        changeStatus(token, id, "PROCESSING")
                .andExpect(status().isForbidden())
                .andExpect(status().reason("Only WAREHOUSE can move an order to PROCESSING, a customer can only cancel it"));
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);

        changeStatus(token, id, "CANCELLED").andExpect(status().isOk());
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(milk)).isEqualTo(10);

        Long assembling = createAndGetId(token, items(milk, 1));
        changeStatus(warehouse, assembling, "PROCESSING").andExpect(status().isOk());
        changeStatus(warehouse, assembling, "READY").andExpect(status().isOk());
        changeStatus(token, assembling, "COMPLETED").andExpect(status().isForbidden());
    }

    @Test
    void warehouseSeesAndProcessesOrdersOfAllCustomers() throws Exception {
        Long mine = createAndGetId(token, items(milk, 1));
        Long others = createAndGetId(register("other@example.com"), items(bread, 1));

        mvc.perform(get("/orders").header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(others))
                .andExpect(jsonPath("$[1].id").value(mine));
        getOrder(warehouse, others).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Bread"));
        mvc.perform(get("/orders/{id}/history", others).header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(status().isOk());

        changeStatus(warehouse, others, "PROCESSING").andExpect(status().isOk());
        assertThat(orderRepository.findById(others).orElseThrow().getStatus()).isEqualTo(OrderStatus.PROCESSING);
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

        mvc.perform(delete("/products/{id}", milk).header("Authorization", TestUsers.bearer(warehouse)))
                .andExpect(status().isConflict());
        assertThat(productRepository.existsById(milk)).isTrue();
    }

    // --- helpers ---

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "secret123"}
                        """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(body, "$.access_token");
        // without an address an order can't be placed
        TestUsers.address(mvc, accessToken, "Москва");
        return accessToken;
    }

    private Integer product(String name, int quantity, String price) {
        return productRepository.save(Products.builder().name(name).quantity(quantity).price(new BigDecimal(price)).build())
                .getId();
    }

    private int stock(Integer productId) {
        return jdbc.queryForObject("select quantity from products where id = ?", Integer.class, productId);
    }

    private static String withAddress(String itemsBody, Long addressId) {
        return itemsBody.substring(0, itemsBody.lastIndexOf('}')) + ", \"address_id\": " + addressId + "}";
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
