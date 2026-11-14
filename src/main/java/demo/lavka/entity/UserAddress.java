package demo.lavka.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A delivery address of a user. A user can have several addresses.
 */
@Entity
@Table(name = "user_addresses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    // format depends on the country, so it is not checked
    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 100)
    private String country;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "street", nullable = false, length = 300)
    private String street;

    // text, not a number: "12к2", "5А"
    @Column(name = "house", nullable = false, length = 20)
    private String house;

    // null for a private house
    @Column(name = "apartment", length = 20)
    private String apartment;

    // first and last name of whoever receives the order
    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;

    // optional: entrance, floor, intercom code and so on
    @Column(name = "courier_note", length = 500)
    private String courierNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserAddress(User user, String postalCode, String country, String city, String street,
                       String house, String apartment, String recipientName, String courierNote) {
        this.user = user;
        update(postalCode, country, city, street, house, apartment, recipientName, courierNote);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Changes the address itself; the owner stays the same.
     */
    public void update(String postalCode, String country, String city, String street,
                       String house, String apartment, String recipientName, String courierNote) {
        this.postalCode = postalCode;
        this.country = country;
        this.city = city;
        this.street = street;
        this.house = house;
        this.apartment = apartment;
        this.recipientName = recipientName;
        this.courierNote = courierNote;
    }
}
