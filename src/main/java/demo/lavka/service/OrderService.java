package demo.lavka.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import demo.lavka.auth.AuthService;
import demo.lavka.entity.DeliveryAddress;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.OrderItem;
import demo.lavka.entity.Orders;
import demo.lavka.entity.Products;
import demo.lavka.entity.Role;
import demo.lavka.entity.User;
import demo.lavka.entity.UserAddress;
import demo.lavka.model.CreateOrderRequest;
import demo.lavka.model.OrderResponse;
import demo.lavka.model.OrderStatusHistoryResponse;
import demo.lavka.model.PageResponse;
import demo.lavka.repository.OrderRepository;
import demo.lavka.repository.ProductRepository;
import demo.lavka.repository.UserAddressRepository;
import demo.lavka.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static demo.lavka.entity.OrderStatus.CANCELLED;

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
    // GET /orders: the page size when none is given, and the largest one a client may ask for
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    // letters, digits, '-' and '_': a UUID fits, and nothing can break a log line or a header
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    // the unique constraint from changeset 009
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "orders_user_idempotency_key_unique";


    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final UserAddressRepository addressRepository;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
                        ProductRepository productRepository, UserAddressRepository addressRepository) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.addressRepository = addressRepository;
    }

    /**
     * One page of orders, newest first: a customer's own ones, or every order for the warehouse. The page size has
     * an upper limit, so one request can't load the whole table with all items. See {@link OrderRepository} for
     * why it takes two queries.
     *
     * @param page from 0
     * @param size 1..{@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> findAll(User actor, int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0, got " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be between 1 and " + MAX_PAGE_SIZE + ", got " + size);
        }
        // the offset goes to SQL as an int: a huge page number would overflow it
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is too large");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Long> ids = actor.getRole() == Role.WAREHOUSE
                ? orderRepository.findAllIds(pageable)
                : orderRepository.findIdsByUserId(actor.getId(), pageable);
        if (ids.isEmpty()) {
            return PageResponse.of(ids, List.of());
        }
        Map<Long, Orders> orders = orderRepository.findWithItemsByIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(Orders::getId, Function.identity()));
        List<OrderResponse> content = ids.getContent().stream()
                .map(orders::get)
                .map(OrderResponse::from)
                .toList();
        return PageResponse.of(ids, content);
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
     * The order and whether it was placed by this request or found from an earlier one with the same
     * Idempotency-Key ({@code replayed}).
     */
    public record CreateResult(OrderResponse order, boolean replayed) {
    }

    /**
     * Creates the order and takes the items from stock in one transaction: either everything is reserved,
     * or nothing changes. The order goes to the address from the request, or to the active one if none is given;
     * a copy of it is stored in the order.
     * <p>
     * <b>Idempotency.</b> With a key (header {@code Idempotency-Key}) a retry doesn't place a second order: the
     * response got lost, the client sent the request again, the user pressed the button twice. The key is checked
     * twice: before anything is locked (the usual retry, the order was committed long ago) and once more after the
     * products are locked. The second check covers a retry that arrives while the first request is still running:
     * the same key means the same products, so the retry waits for the first request's lock, and when it gets the
     * rows the first order is committed and visible. The unique constraint on (user_id, idempotency_key) is the
     * last line of defence.
     *
     * @param idempotencyKey null when the client sent none: then every request is a new order, as before
     * @throws ResponseStatusException 422 if the key was already used for a different request
     */
    @Transactional
    public CreateResult create(Long userId, CreateOrderRequest request, String idempotencyKey) {
        Set<Integer> productIds = new HashSet<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (!productIds.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product " + item.productId() + " is listed twice, put the total quantity in one line");
            }
        }
        String requestHash = null;
        if (idempotencyKey != null) {
            checkIdempotencyKey(idempotencyKey);
            requestHash = requestHash(request);
            // before the address: a retry must get its order even if the address was deleted since
            Optional<CreateResult> earlier = earlierOrder(userId, idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }

        // checked before products are locked: a wrong address shouldn't hold up other orders
        DeliveryAddress delivery = DeliveryAddress.copyOf(deliveryAddress(userId, request.addressId()));

        Map<Integer, Products> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Products::getId, Function.identity()));

        if (idempotencyKey != null) {
            // a parallel request with this key may have committed while we were waiting for the lock
            Optional<CreateResult> earlier = earlierOrder(userId, idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }

        // a reference, not a SELECT: only the id is needed for user_id
        User user = userRepository.getReferenceById(userId);
        Orders order = new Orders(user, delivery);
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
        if (idempotencyKey != null) {
            order.rememberIdempotencyKey(idempotencyKey, requestHash);
        }

        try {
            // flush here, not on commit: a clash on the unique key must become a clear answer, not a 500
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null && String.valueOf(e.getMostSpecificCause().getMessage())
                    .contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
                // same key, different products: no shared lock, both passed the checks; the other one won
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another order with this Idempotency-Key is being placed right now");
            }
            throw e;
        }
        log.info("Order created: id={} userId={} items={} total={} idempotencyKey={}", order.getId(), userId,
                order.getItems().size(), order.getTotal(), idempotencyKey != null);
        return new CreateResult(OrderResponse.from(order), false);
    }

    private Optional<CreateResult> earlierOrder(Long userId, String idempotencyKey, String requestHash) {
        return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(earlier -> {
            if (!earlier.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Idempotency-Key was already used for a different order: send a new key for a new order");
            }
            log.info("Order replayed: id={} userId={} (same Idempotency-Key)", earlier.getId(), userId);
            return new CreateResult(OrderResponse.from(earlier), true);
        });
    }

    private static void checkIdempotencyKey(String key) {
        if (!IDEMPOTENCY_KEY_FORMAT.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1 to 100 characters: letters, digits, '-' and '_' (a UUID fits)");
        }
    }

    /**
     * SHA-256 of what the request asks for. Lines are sorted by product, so the same order sent with its lines
     * in another order is still the same request; "no address" stays "no address" (the active one at that time).
     */
    static String requestHash(CreateOrderRequest request) {
        String canonical = request.items().stream()
                .sorted(Comparator.comparing(CreateOrderRequest.Item::productId))
                .map(item -> item.productId() + "x" + item.quantity())
                .collect(Collectors.joining(",", "items=", ";address=" + request.addressId()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /**
     * Needs the transaction: the order is changed in place and dirty checking writes it on commit.
     * <p>
     * The order row is locked first ({@code FOR UPDATE}), so parallel changes of one order run one after another:
     * the second one reads the status the first one wrote. Without the lock two cancels both pass the transition
     * check and return the items to stock twice. Lock order is always order, then products by id, and placing
     * an order locks only products, so there is no deadlock.
     */
    @Transactional
    public OrderResponse changeStatus(User actor, Long orderId, OrderStatus newStatus) {
        // a customer locks only own orders: the owner check is in the locking query, not after it
        Orders order = (actor.getRole() == Role.WAREHOUSE
                ? orderRepository.findByIdForUpdate(orderId)
                : orderRepository.findByIdAndUserIdForUpdate(orderId, actor.getId()))
                .orElseThrow(() -> notFound(orderId));
        // assembling and delivering is the warehouse's job, a customer can only cancel;
        // the response doesn't name roles, the log does
        if (actor.getRole() != Role.WAREHOUSE && newStatus != CANCELLED) {
            log.warn("Access denied: userId={} role={} tried to move order {} to {}, only {} can",
                    actor.getId(), actor.getRole(), orderId, newStatus, Role.WAREHOUSE);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, AuthService.ACCESS_DENIED);
        }
        OrderStatus current = order.getStatus();
        // the allowed transitions are defined in OrderStatus
        if (!current.canChangeTo(newStatus)) {
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

    private UserAddress deliveryAddress(Long userId, Long addressId) {
        if (addressId != null) {
            // someone else's address is "not found", same as for orders
            return addressRepository.findByIdAndUserId(addressId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Address " + addressId + " not found"));
        }
        return addressRepository.findByUserIdAndActiveTrue(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No delivery address: add one with POST /users/me/addresses"));
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
        return order.orElseThrow(() -> notFound(orderId));
    }

    private static ResponseStatusException notFound(Long orderId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + orderId + " not found");
    }

}
