package demo.lavka.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Where an order goes: a copy of a {@link UserAddress} taken when the order is placed, stored in the
 * {@code delivery_*} columns of {@code orders}. Later edits or deleting the address don't change it.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
public class DeliveryAddress {

    @Column(name = "delivery_recipient_name", length = 200)
    private String recipientName;

    @Column(name = "delivery_postal_code", length = 20)
    private String postalCode;

    @Column(name = "delivery_country", length = 100)
    private String country;

    @Column(name = "delivery_city", length = 100)
    private String city;

    @Column(name = "delivery_street", length = 300)
    private String street;

    @Column(name = "delivery_house", length = 20)
    private String house;

    @Column(name = "delivery_apartment", length = 20)
    private String apartment;

    @Column(name = "delivery_courier_note", length = 500)
    private String courierNote;

    public static DeliveryAddress copyOf(UserAddress address) {
        DeliveryAddress copy = new DeliveryAddress();
        copy.recipientName = address.getRecipientName();
        copy.postalCode = address.getPostalCode();
        copy.country = address.getCountry();
        copy.city = address.getCity();
        copy.street = address.getStreet();
        copy.house = address.getHouse();
        copy.apartment = address.getApartment();
        copy.courierNote = address.getCourierNote();
        return copy;
    }

}
