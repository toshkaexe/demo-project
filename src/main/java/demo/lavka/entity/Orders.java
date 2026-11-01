package demo.lavka.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")  // Foreign key column
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // items and history are saved and deleted together with the order
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    protected Orders() {
        // for JPA
    }

    public Orders(User user) {
        this.user = user;
        statusHistory.add(new OrderStatusHistory(this, null, OrderStatus.CREATED, user));
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Adds a line with the product's current price. Stock is not touched here, that is the service's job.
     */
    public void addItem(Products product, int quantity) {
        items.add(new OrderItem(this, product, quantity, product.getPrice()));
    }

    /**
     * Moves the order to another status and writes the change to the history, so no change can skip it.
     * {@code completedAt} is set exactly when the order becomes {@link OrderStatus#COMPLETED},
     * the database checks the same rule.
     *
     * @param changedBy who made the change, null for the system
     */
    public void changeStatus(OrderStatus newStatus, User changedBy) {
        statusHistory.add(new OrderStatusHistory(this, status, newStatus, changedBy));
        status = newStatus;
        completedAt = newStatus == OrderStatus.COMPLETED ? Instant.now() : null;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    // read-only views: items and history change only through addItem and changeStatus
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    public BigDecimal getTotal() {
        return items.stream().map(OrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
