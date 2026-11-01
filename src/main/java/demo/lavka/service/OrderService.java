package demo.lavka.service;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Set;

import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.OrderItem;
import demo.lavka.entity.Orders;
import demo.lavka.entity.Products;
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
 * Orders of the current user. Every method takes the user's id from the access token,
 * so a user can't see or change someone else's orders.
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
    public List<OrderResponse> findAll(Long userId) {
        return orderRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long userId, Long orderId) {
        return OrderResponse.from(getOrThrow(userId, orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> history(Long userId, Long orderId) {
        return getOrThrow(userId, orderId).getStatusHistory().stream()
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
    public OrderResponse changeStatus(Long userId, Long orderId, OrderStatus newStatus) {
        Orders order = getOrThrow(userId, orderId);
        OrderStatus current = order.getStatus();
        if (!TRANSITIONS.get(current).contains(newStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Can't change order status from " + current + " to " + newStatus);
        }
        if (newStatus == CANCELLED) {
            returnToStock(order);
        }
        order.changeStatus(newStatus, userRepository.getReferenceById(userId));
        log.info("Order status changed: id={} userId={} {} -> {}", orderId, userId, current, newStatus);
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

    private Orders getOrThrow(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + orderId + " not found"));
    }

}
