package demo.lavka.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The user the access token belongs to, already checked by {@link RoleInterceptor}. Works only on endpoints
 * marked {@link Authenticated} or {@link RequireRole}; anywhere else it is a programming error and fails loudly.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
