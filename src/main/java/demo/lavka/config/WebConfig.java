package demo.lavka.config;

import demo.lavka.auth.RoleInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsConfig.class)
public class WebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roleInterceptor;
    private final CorsConfig corsConfig;

    public WebConfig(RoleInterceptor roleInterceptor, CorsConfig corsConfig) {
        this.roleInterceptor = roleInterceptor;
        this.corsConfig = corsConfig;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor);
    }

    /**
     * Lets the frontend (a separate app on another origin) call the API from the browser.
     * Tokens travel in the Authorization header, not in cookies, so credentials stay off.
     * Preflight (OPTIONS) requests are answered before RoleInterceptor checks roles:
     * their handler is not a controller method, so the interceptor lets them through.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsConfig.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE)
                // the frontend reads the new order/product URL from Location after a 201
                .exposedHeaders(HttpHeaders.LOCATION)
                .allowCredentials(false)
                .maxAge(3600);
    }

}
