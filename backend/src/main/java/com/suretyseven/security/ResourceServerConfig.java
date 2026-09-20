package com.suretyseven.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Protects the actual API (/applications/**) with OAuth2 bearer-token auth,
 * validating tokens issued by AuthorizationServerConfig.
 *
 * Two scopes are enforced: "applications.write" for creating applications,
 * "applications.read" for reading/listing them. A broker granted only one
 * scope (see the Broker seed data) genuinely cannot perform the other
 * action -- this is real authorization, not just authentication.
 *
 * WHO can see WHICH applications (a broker only ever seeing their own) is
 * NOT enforced here -- scopes are coarse-grained ("can you write at all")
 * while row-level ownership is a data-shape concern, enforced in
 * ApplicationService against the broker_id extracted from the validated
 * token. Mixing the two into one filter-chain rule would only handle the
 * list endpoint, not "GET a specific ID that isn't yours", so ownership is
 * deliberately handled once, in one place, at the service layer.
 *
 * /oauth2/**, /actuator/**, /external/** (mock Applicant API) and /mock/**
 * (mock downstream inspection) are intentionally NOT resource-server
 * protected: the first is handled entirely by AuthorizationServerConfig's
 * own filter chain (@Order(1), matched first), and the mocks stand in for
 * systems that, in reality, would live outside this API's trust boundary
 * entirely.
 */
@Configuration
public class ResourceServerConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                .securityMatcher("/**")
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies/CSRF surface
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS preflight; see CorsConfig
                        .requestMatchers("/actuator/**", "/external/**", "/mock/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/applications").hasAuthority("SCOPE_applications.write")
                        .requestMatchers(HttpMethod.GET, "/applications/**").hasAuthority("SCOPE_applications.read")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));

        return http.build();
    }
}
