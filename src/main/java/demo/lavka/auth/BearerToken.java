package demo.lavka.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pulls the token out of an {@code Authorization: Bearer <token>} header.
 */
public final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    public static String from(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)
                || authorizationHeader.length() == PREFIX.length()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        return authorizationHeader.substring(PREFIX.length());
    }

}
