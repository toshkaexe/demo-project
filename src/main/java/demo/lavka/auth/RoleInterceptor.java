package demo.lavka.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequireRole}. Runs after the controller method is chosen but before its arguments
 * (and the {@code @Valid} body) are resolved.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public RoleInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequireRole required = method.getMethodAnnotation(RequireRole.class);
            if (required != null) {
                authService.requireRole(BearerToken.from(request.getHeader(HttpHeaders.AUTHORIZATION)), required.value());
            }
        }
        return true;
    }

}
