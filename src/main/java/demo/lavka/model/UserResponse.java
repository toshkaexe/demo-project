package demo.lavka.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.lavka.entity.User;

public record UserResponse(
        Long id,
        String email,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt(), user.getUpdatedAt());
    }

}
