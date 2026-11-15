package demo.lavka.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.lavka.entity.DeliveryAddress;
import demo.lavka.entity.OrderItem;
import demo.lavka.entity.OrderStatus;
import demo.lavka.entity.Orders;

public record OrderResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        // who placed the order, for the warehouse
        @JsonProperty("user_email") String userEmail,
        OrderStatus status,
        List<Item> items,
        BigDecimal total,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("completed_at") Instant completedAt,
        // null only for orders placed before delivery addresses existed
        @JsonProperty("delivery_address") Delivery deliveryAddress) {

    /**
     * Reads items and their products: call it inside a transaction.
     */
    public static OrderResponse from(Orders order) {
        return new OrderResponse(order.getId(), order.getUser().getId(), order.getUser().getEmail(), order.getStatus(),
                order.getItems().stream().map(Item::from).toList(), order.getTotal(),
                order.getCreatedAt(), order.getCompletedAt(), Delivery.from(order.getDeliveryAddress()));
    }

    /**
     * The address as it was when the order was placed.
     */
    public record Delivery(
            @JsonProperty("recipient_name") String recipientName,
            @JsonProperty("postal_code") String postalCode,
            String country,
            String city,
            String street,
            String house,
            String apartment,
            @JsonProperty("courier_note") String courierNote) {

        static Delivery from(DeliveryAddress address) {
            if (address == null) {
                return null;
            }
            return new Delivery(address.getRecipientName(), address.getPostalCode(), address.getCountry(),
                    address.getCity(), address.getStreet(), address.getHouse(), address.getApartment(),
                    address.getCourierNote());
        }

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
