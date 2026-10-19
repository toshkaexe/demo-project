package demo.lavka.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtConfig(String secret, Duration accessTtl, Duration refreshTtl) {
}
