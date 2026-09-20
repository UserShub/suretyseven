package com.suretyseven.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Frontend (Netlify) and backend (Render) are on different origins, so CORS
 * has to be explicit.
 *
 * This is registered as an actual CorsFilter with HIGHEST_PRECEDENCE
 * ordering -- NOT via WebMvcConfigurer.addCorsMappings() -- deliberately.
 * addCorsMappings() only wires CORS into Spring MVC's dispatcher-level
 * handling, but CorrelationIdFilter and Spring Security's OAuth2 resource
 * server filter are raw servlet
 * Filters that run *before* the dispatcher ever sees the request. That
 * meant a browser's CORS preflight (OPTIONS, sent with no Authorization
 * header) was hitting the resource-server auth check first, getting rejected
 * with 401, and
 * never reaching the code that would have attached CORS headers -- which
 * the browser reports as a bare CORS failure, not a 401. Registering CORS
 * as its own Filter with Ordered.HIGHEST_PRECEDENCE guarantees it runs
 * first in the actual servlet filter chain and fully answers preflight
 * requests itself, before Spring Security or CorrelationIdFilter ever run.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
