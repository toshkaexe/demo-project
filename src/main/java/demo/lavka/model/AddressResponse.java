package demo.lavka.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.lavka.entity.UserAddress;

public record AddressResponse(
        Long id,
        @JsonProperty("postal_code") String postalCode,
        String country,
        String city,
        String street,
        String house,
        String apartment,
        @JsonProperty("recipient_name") String recipientName,
        @JsonProperty("courier_note") String courierNote,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(address.getId(), address.getPostalCode(), address.getCountry(), address.getCity(),
                address.getStreet(), address.getHouse(), address.getApartment(), address.getRecipientName(),
                address.getCourierNote(), address.isActive(), address.getCreatedAt(), address.getUpdatedAt());
    }

}
