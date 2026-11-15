package demo.lavka.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins of the frontend that may call the API from a browser (e.g. {@code http://localhost:3000}).
 * Set in application-local.yaml; several origins are comma-separated.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsConfig(List<String> allowedOrigins) {

    public CorsConfig {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalStateException("cors.allowed-origins is not set: list the frontend origins");
        }
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException("cors.allowed-origins must list exact origins, not *");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
