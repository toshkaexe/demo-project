package demo.lavka.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint needs a valid access token, any role: 401 without one. Like {@link RequireRole}, it is checked
 * by {@link RoleInterceptor} before the request body is read and validated, so a client without a token gets
 * 401, never a 400 with details of the body. On a class it covers every endpoint of the controller.
 * The user is passed to the method as a {@link CurrentUser} parameter.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Authenticated {
}
