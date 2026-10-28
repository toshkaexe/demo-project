package demo.lavka.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link JWTService#validateToken} when a token can't be trusted. Becomes a 401 response.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidTokenException extends RuntimeException {

    public enum Reason {
        /** not a JWT, unsigned ({@code alg: none}) or otherwise broken */
        MALFORMED,
        /** signature doesn't match our key: forged or changed token */
        BAD_SIGNATURE,
        /** {@code exp} is in the past */
        EXPIRED,
        /** refresh token where an access token is expected, or vice versa */
        WRONG_TYPE,
        /** revoked by logout or already exchanged for a new pair */
        REVOKED
    }

    private final Reason reason;

    public InvalidTokenException(Reason reason) {
        // the client only needs to know whether to refresh or to log in again
        super(reason == Reason.EXPIRED ? "Token expired" : "Invalid token");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

}
