package demo.lavka.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Credentials(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 100) String password) {
}
