package demo.lavka.controller;

import java.net.URI;
import java.util.List;

import demo.lavka.auth.Authenticated;
import demo.lavka.auth.CurrentUser;
import demo.lavka.entity.User;
import demo.lavka.model.ChangeOrderStatusRequest;
import demo.lavka.model.CreateOrderRequest;
import demo.lavka.model.OrderResponse;
import demo.lavka.model.OrderStatusHistoryResponse;
import demo.lavka.model.PageResponse;
import demo.lavka.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * A customer works with own orders and can only cancel them, the warehouse sees every order and moves it
 * through the statuses (see {@link OrderService}). There is no DELETE: order history must not disappear,
 * an order is cancelled instead (status CANCELLED).
 */
@RestController
@Authenticated
@RequestMapping("/orders")
public class OrderController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * One page of orders, newest first: {@code ?page=0&size=20}. {@code size} is at most
     * {@value OrderService#MAX_PAGE_SIZE}, otherwise 400.
     */
    @GetMapping
    public PageResponse<OrderResponse> findAll(@CurrentUser User user,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "" + OrderService.DEFAULT_PAGE_SIZE) int size) {
        return orderService.findAll(user, page, size);
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@CurrentUser User user, @PathVariable Long id) {
        return orderService.findById(user, id);
    }

    /**
     * 201 Created with a {@code Location: /orders/{id}} header. The ordered items are taken from stock.
     * <p>
     * With an {@code Idempotency-Key} header a retry returns the order the first request placed: the same 201,
     * {@code Location} and body (its current state), plus {@code Idempotent-Replayed: true}. Nothing is taken from
     * stock again. The same key with a different body is 422.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@CurrentUser User user,
                                                @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateResult result = orderService.create(user.getId(), request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.order().id())
                .toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(result.order());
    }

    /**
     * Every status change of the order, oldest first.
     */
    @GetMapping("/{id}/history")
    public List<OrderStatusHistoryResponse> history(@CurrentUser User user, @PathVariable Long id) {
        return orderService.history(user, id);
    }

    /**
     * A customer may only cancel ({@code CANCELLED}), other statuses need the warehouse role (403).
     * Cancelling returns the items to stock.
     */
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@CurrentUser User user,
                                      @PathVariable Long id,
                                      @Valid @RequestBody ChangeOrderStatusRequest request) {
        return orderService.changeStatus(user, id, request.status());
    }

}
