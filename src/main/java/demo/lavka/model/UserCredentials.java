package demo.lavka.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCredentials(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 100) String password) {
}
