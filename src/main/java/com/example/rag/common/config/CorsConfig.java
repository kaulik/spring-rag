package com.example.rag.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final GuardrailProperties guardrailProperties;

    /**
     * CORS via a filter with a per-request CorsConfigurationSource (instead of the
     * startup-time WebMvcConfigurer registry), so guardrails.allowed-origins changes
     * applied through /actuator/refresh take effect without a restart.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfigurationSource source = request -> {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(guardrailProperties.getAllowedOrigins().split(",")));
            config.setAllowedMethods(List.of("POST", "OPTIONS"));
            config.setAllowedHeaders(List.of("Content-Type", "X-API-Key"));
            config.setMaxAge(3600L);
            return config;
        };
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(0);
        return bean;
    }
}
