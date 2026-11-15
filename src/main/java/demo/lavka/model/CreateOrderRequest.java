package demo.lavka.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param addressId one of the user's delivery addresses; when it is left out the order goes to the active address
 */
public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Item> items,
        @JsonProperty("address_id") Long addressId) {

    public record Item(
            @JsonProperty("product_id") @NotNull Integer productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

}
