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
import demo.lavka.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> findAll(@CurrentUser User user) {
        return orderService.findAll(user);
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@CurrentUser User user, @PathVariable Long id) {
        return orderService.findById(user, id);
    }

    /**
     * 201 Created with a {@code Location: /orders/{id}} header. The ordered items are taken from stock.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@CurrentUser User user,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(user.getId(), request);
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
