package demo.lavka.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: loading an order doesn't pull the user from the database until it's needed
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Orders() {
        // for JPA
    }

    public Orders(User user) {
        this.user = user;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Moves the order to another status. {@code completedAt} is set exactly when the order becomes
     * {@link OrderStatus#COMPLETED}, the database checks the same rule.
     */
    public void changeStatus(OrderStatus newStatus) {
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

}
