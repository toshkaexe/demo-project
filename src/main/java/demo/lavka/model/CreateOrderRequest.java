package demo.lavka.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Item> items) {

    public record Item(
            @JsonProperty("product_id") @NotNull Integer productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

}
