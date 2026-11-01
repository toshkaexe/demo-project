package demo.lavka.service;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.OrderItem;
import demo.lavka.entity.Orders;
import demo.lavka.entity.Products;
import demo.lavka.entity.Role;
import demo.lavka.entity.User;
import demo.lavka.model.CreateOrderRequest;
import demo.lavka.model.OrderResponse;
import demo.lavka.model.OrderStatusHistoryResponse;
import demo.lavka.repository.OrderRepository;
import demo.lavka.repository.ProductRepository;
import demo.lavka.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static demo.lavka.entity.OrderStatus.CANCELLED;
import static demo.lavka.entity.OrderStatus.COMPLETED;
import static demo.lavka.entity.OrderStatus.CREATED;
import static demo.lavka.entity.OrderStatus.PROCESSING;
import static demo.lavka.entity.OrderStatus.READY;

/**
 * Who may do what, by the role of the user from the access token:
 * <ul>
 *     <li>{@link Role#CUSTOMER} sees only own orders and may only cancel them;</li>
 *     <li>{@link Role#WAREHOUSE} sees every order and moves it through all statuses.</li>
 * </ul>
 * Anyone may place an order for themselves. An order the user may not see is "not found" (404).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * Allowed status changes: forward one step at a time, or cancel until the order is completed.
     * COMPLETED and CANCELLED are final.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PROCESSING, CANCELLED),
            PROCESSING, EnumSet.of(READY, CANCELLED),
            READY, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
                        ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll(User actor) {
        List<Orders> orders = actor.getRole() == Role.WAREHOUSE
                ? orderRepository.findAllByOrderByIdDesc()
                : orderRepository.findByUserIdOrderByIdDesc(actor.getId());
        return orders.stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(User actor, Long orderId) {
        return OrderResponse.from(getOrThrow(actor, orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> history(User actor, Long orderId) {
        return getOrThrow(actor, orderId).getStatusHistory().stream()
                .map(OrderStatusHistoryResponse::from)
                .toList();
    }

    /**
     * Creates the order and takes the items from stock in one transaction: either everything is reserved,
     * or nothing changes.
     */
    @Transactional
    public OrderResponse create(Long userId, CreateOrderRequest request) {
        Set<Integer> productIds = new HashSet<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (!productIds.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product " + item.productId() + " is listed twice, put the total quantity in one line");
            }
        }

        Map<Integer, Products> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Products::getId, Function.identity()));

        // a reference, not a SELECT: only the id is needed for user_id
        User user = userRepository.getReferenceById(userId);
        Orders order = new Orders(user);
        for (CreateOrderRequest.Item item : request.items()) {
            Products product = products.get(item.productId());
            if (product == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product " + item.productId() + " not found");
            }
            if (product.getQuantity() < item.quantity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough stock for product " + product.getId()
                        + ": requested " + item.quantity() + ", available " + product.getQuantity());
            }
            product.setQuantity(product.getQuantity() - item.quantity());
            order.addItem(product, item.quantity());
        }

        orderRepository.save(order);
        log.info("Order created: id={} userId={} items={} total={}", order.getId(), userId,
                order.getItems().size(), order.getTotal());
        return OrderResponse.from(order);
    }

    /**
     * Needs the transaction: the order is changed in place and dirty checking writes it on commit.
     */
    @Transactional
    public OrderResponse changeStatus(User actor, Long orderId, OrderStatus newStatus) {
        Orders order = getOrThrow(actor, orderId);
        // assembling and delivering is the warehouse's job, a customer can only cancel
        if (actor.getRole() != Role.WAREHOUSE && newStatus != CANCELLED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only " + Role.WAREHOUSE + " can move an order to " + newStatus + ", a customer can only cancel it");
        }
        OrderStatus current = order.getStatus();
        if (!TRANSITIONS.get(current).contains(newStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Can't change order status from " + current + " to " + newStatus);
        }
        if (newStatus == CANCELLED) {
            returnToStock(order);
        }
        order.changeStatus(newStatus, userRepository.getReferenceById(actor.getId()));
        log.info("Order status changed: id={} by userId={} ({}) {} -> {}", orderId, actor.getId(), actor.getRole(),
                current, newStatus);
        return OrderResponse.from(order);
    }

    // a cancelled order gives its items back, under the same lock as when they were taken
    private void returnToStock(Orders order) {
        Map<Integer, Integer> returned = order.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(), OrderItem::getQuantity));
        for (Products product : productRepository.findAllByIdForUpdate(returned.keySet())) {
            product.setQuantity(product.getQuantity() + returned.get(product.getId()));
        }
    }

    private Orders getOrThrow(User actor, Long orderId) {
        Optional<Orders> order = actor.getRole() == Role.WAREHOUSE
                ? orderRepository.findWithItemsById(orderId)
                : orderRepository.findByIdAndUserId(orderId, actor.getId());
        return order
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + orderId + " not found"));
    }

}
