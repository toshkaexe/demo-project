package demo.lavka.auth;

import demo.lavka.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequireRole} and {@link Authenticated}. Runs after the controller method is chosen but before
 * its arguments (and the {@code @Valid} body) are resolved, so a request without a valid token gets 401 (and a
 * wrong role 403) before anything about its body. The user is stored in the request for {@link CurrentUser}.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    static final String USER_ATTRIBUTE = RoleInterceptor.class.getName() + ".user";

    private final AuthService authService;

    public RoleInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            User user = null;
            RequireRole role = method.getMethodAnnotation(RequireRole.class);
            if (role != null) {
                user = authService.requireRole(BearerToken.from(request.getHeader(HttpHeaders.AUTHORIZATION)), role.value());
            } else if (method.hasMethodAnnotation(Authenticated.class)
                    || method.getBeanType().isAnnotationPresent(Authenticated.class)) {
                user = authService.authenticate(BearerToken.from(request.getHeader(HttpHeaders.AUTHORIZATION)));
            }
            if (user != null) {
                request.setAttribute(USER_ATTRIBUTE, user);
            }
        }
        return true;
    }

}
