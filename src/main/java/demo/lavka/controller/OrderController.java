package demo.lavka.controller;

import java.net.URI;
import java.util.List;

import demo.lavka.auth.AuthService;
import demo.lavka.auth.BearerToken;
import demo.lavka.entity.User;
import demo.lavka.model.ChangeOrderStatusRequest;
import demo.lavka.model.CreateOrderRequest;
import demo.lavka.model.OrderResponse;
import demo.lavka.model.OrderStatusHistoryResponse;
import demo.lavka.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * A customer works with own orders and can only cancel them, the warehouse sees every order and moves it
 * through the statuses (see {@link OrderService}). There is no DELETE: order history must not disappear,
 * an order is cancelled instead (status CANCELLED).
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final AuthService authService;
    private final OrderService orderService;

    public OrderController(AuthService authService, OrderService orderService) {
        this.authService = authService;
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> findAll(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return orderService.findAll(user(authorization));
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                  @PathVariable Long id) {
        return orderService.findById(user(authorization), id);
    }

    /**
     * 201 Created with a {@code Location: /orders/{id}} header. The ordered items are taken from stock.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(user(authorization).getId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Every status change of the order, oldest first.
     */
    @GetMapping("/{id}/history")
    public List<OrderStatusHistoryResponse> history(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                    @PathVariable Long id) {
        return orderService.history(user(authorization), id);
    }

    /**
     * A customer may only cancel ({@code CANCELLED}), other statuses need the warehouse role (403).
     * Cancelling returns the items to stock.
     */
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                      @PathVariable Long id,
                                      @Valid @RequestBody ChangeOrderStatusRequest request) {
        return orderService.changeStatus(user(authorization), id, request.status());
    }

    private User user(String authorization) {
        return authService.authenticate(BearerToken.from(authorization));
    }

}
