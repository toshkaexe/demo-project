package demo.lavka.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.lavka.entity.OrderItem;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.Orders;

public record OrderResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        OrderStatus status,
        List<Item> items,
        BigDecimal total,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("completed_at") Instant completedAt) {

    /**
     * Reads items and their products: call it inside a transaction.
     */
    public static OrderResponse from(Orders order) {
        return new OrderResponse(order.getId(), order.getUser().getId(), order.getStatus(),
                order.getItems().stream().map(Item::from).toList(), order.getTotal(),
                order.getCreatedAt(), order.getCompletedAt());
    }

    public record Item(
            @JsonProperty("product_id") Integer productId,
            String name,
            int quantity,
            // price per item at the moment of ordering
            BigDecimal price,
            BigDecimal amount) {

        static Item from(OrderItem item) {
            return new Item(item.getProduct().getId(), item.getProduct().getName(), item.getQuantity(),
                    item.getPrice(), item.getAmount());
        }

    }

}
