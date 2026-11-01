package demo.lavka.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import demo.lavka.entity.Role;

/**
 * The endpoint needs an access token of a user with this role: 401 without a valid token, 403 for another role.
 * Checked by {@link RoleInterceptor} before the request body is read, so an unauthorized client never gets
 * validation errors back.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    Role value();

}
