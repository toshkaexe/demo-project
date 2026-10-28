package demo.lavka.auth;

/**
 * Value of the {@code typ} claim: tells an access token from a refresh token.
 */
public enum TokenType {

    ACCESS("access"),
    REFRESH("refresh");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }

}
