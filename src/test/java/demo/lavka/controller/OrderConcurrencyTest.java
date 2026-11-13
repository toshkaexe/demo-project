package demo.lavka.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import demo.lavka.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Several status changes of one order at the same moment, in real parallel transactions.
 * Without a lock on the order row every transaction reads the old status, every one decides the change is allowed,
 * and a cancel returns the items to stock once per request.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderConcurrencyTest {

    // a race needs a few tries to show up; with the lock the result must be the same every time
    private static final int ROUNDS = 20;
    private static final int PARALLEL_REQUESTS = 4;

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
    private String customer;
    private String warehouse;
    private Integer milk;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        customer = TestUsers.customer(mvc, "anton@example.com");
        warehouse = TestUsers.warehouse(mvc, jdbc, "warehouse@example.com");
        milk = productRepository.save(Products.builder().name("Milk").quantity(10).price(new BigDecimal("89.90")).build())
                .getId();
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void parallelCancelsReturnStockOnlyOnce() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            // a cancel is allowed only from CREATED
            Long orderId = createOrder(2);                         // stock 10 -> 8

            // the customer and the warehouse press "cancel" at the same moment, a few times
            List<Integer> statuses = inParallel(i -> changeStatus(i % 2 == 0 ? customer : warehouse, orderId, "CANCELLED"));

            assertThat(statuses).as("round %d: one cancel wins, the rest see CANCELLED", round)
                    .containsOnlyOnce(200).containsOnly(200, 409);
            assertThat(stock()).as("round %d: stock is returned once", round).isEqualTo(10);
            assertThat(cancelRecords(orderId)).as("round %d: one CANCELLED record in history", round).isEqualTo(1);
        }
    }

    @Test
    void assembleAndCancelAtTheSameTimeLeaveOneConsistentResult() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            // both CREATED -> PROCESSING and CREATED -> CANCELLED are allowed, so only the order of commits decides
            Long orderId = createOrder(2);

            // the warehouse starts assembling while the customer cancels
            List<Integer> statuses = inParallel(i -> i % 2 == 0
                    ? changeStatus(warehouse, orderId, "PROCESSING")
                    : changeStatus(customer, orderId, "CANCELLED"));

            assertThat(statuses).as("round %d: only the first change wins", round)
                    .containsOnlyOnce(200).containsOnly(200, 409);
            String finalStatus = jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
            // assembling: the milk stays taken; cancelled: it is back in stock. Never both, never twice
            assertThat(stock()).as("round %d: stock matches the final status %s", round, finalStatus)
                    .isEqualTo(finalStatus.equals("CANCELLED") ? 10 : 8);
            assertThat(historyAfterCreated(orderId)).as("round %d: one change after CREATED", round).isEqualTo(1);

            // put the milk back for the next round
            jdbc.update("update products set quantity = 10 where id = ?", milk);
        }
    }

    // --- helpers ---

    private interface Request {
        int send(int index) throws Exception;
    }

    // starts all requests at the same moment and returns their HTTP statuses
    private List<Integer> inParallel(Request request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return request.send(index);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Long createOrder(int quantity) throws Exception {
        String body = mvc.perform(post("/orders").header("Authorization", TestUsers.bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"product_id\": " + milk + ", \"quantity\": " + quantity + "}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private int changeStatus(String token, Long orderId, String newStatus) throws Exception {
        return mvc.perform(patch("/orders/{id}/status", orderId).header("Authorization", TestUsers.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"" + newStatus + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int stock() {
        return jdbc.queryForObject("select quantity from products where id = ?", Integer.class, milk);
    }

    private int cancelRecords(Long orderId) {
        return jdbc.queryForObject(
                "select count(*) from order_status_history where order_id = ? and to_status = 'CANCELLED'",
                Integer.class, orderId);
    }

    private int historyAfterCreated(Long orderId) {
        return jdbc.queryForObject(
                "select count(*) from order_status_history where order_id = ? and from_status = 'CREATED'",
                Integer.class, orderId);
    }

}
