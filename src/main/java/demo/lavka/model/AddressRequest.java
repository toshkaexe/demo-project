package demo.lavka.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of create and update: update replaces all fields (PUT semantics). Limits match the columns of
 * {@code user_addresses}. Spaces around values are trimmed; a blank {@code apartment} or {@code courier_note}
 * is stored as null.
 */
public record AddressRequest(
        @JsonProperty("postal_code") @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 100) String country,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 300) String street,
        @NotBlank @Size(max = 20) String house,
        @Size(max = 20) String apartment,
        @JsonProperty("recipient_name") @NotBlank @Size(max = 200) String recipientName,
        @JsonProperty("courier_note") @Size(max = 500) String courierNote) {
}
