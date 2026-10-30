package demo.lavka.repository;

import demo.lavka.TestcontainersConfiguration;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.Orders;
import demo.lavka.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saves orders through JPA, so the database checks (status, completed_at) run against what the entity writes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        cleanUp();
        user = userRepository.save(new User("anton@example.com", "hash"));
    }

    // other test classes delete users, and orders would block that
    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void newOrderIsCreatedAndNotCompleted() {
        Orders order = orderRepository.save(new Orders(user));

        Orders saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCompletedAt()).isNull();
    }

    @Test
    void completedAtIsSetWhenOrderIsCompleted() {
        Orders order = orderRepository.save(new Orders(user));

        for (OrderStatus status : new OrderStatus[] {OrderStatus.PROCESSING, OrderStatus.READY}) {
            order.changeStatus(status);
            order = orderRepository.save(order);
            assertThat(order.getCompletedAt()).isNull();
        }
        order.changeStatus(OrderStatus.COMPLETED);
        order = orderRepository.saveAndFlush(order);

        Orders saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void cancelledOrderHasNoCompletedAt() {
        Orders order = orderRepository.save(new Orders(user));
        order.changeStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(order);

        Orders saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompletedAt()).isNull();
    }

    @Test
    void ordersAreFoundByUser() {
        User other = userRepository.save(new User("other@example.com", "hash"));
        orderRepository.save(new Orders(user));
        orderRepository.save(new Orders(user));
        orderRepository.save(new Orders(other));

        assertThat(orderRepository.findByUserId(user.getId())).hasSize(2);
        assertThat(orderRepository.findByUserId(other.getId())).hasSize(1);
    }

}
