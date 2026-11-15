package demo.lavka.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the hand-written spec at {@code /openapi.yaml} for Swagger UI, and only while Swagger UI is on
 * ({@code springdoc.swagger-ui.enabled}, true in the local profile). The file lives in
 * {@code resources/openapi/}, not in {@code static/}: everything in {@code static/} is public in every profile.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true")
public class OpenApiDocsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi.yaml").addResourceLocations("classpath:/openapi/");
    }

}
