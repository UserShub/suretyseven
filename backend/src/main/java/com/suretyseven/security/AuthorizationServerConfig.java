package com.suretyseven.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Embeds Spring Authorization Server directly in this application, issuing
 * client_credentials tokens for each broker at POST /oauth2/token.
 *
 * Deliberately embedded rather than run as a separate service: it's one
 * fewer thing to deploy/host for free, and it lets the JWKSource bean below
 * be shared directly (in-memory, same JVM) with ResourceServerConfig's
 * JwtDecoder -- so validating a token never needs an HTTP round trip to
 * fetch a JWK set (no startup-order chicken-and-egg problem from a
 * same-app issuer/audience, which a naive issuer-uri-based resource-server
 * setup would hit).
 *
 * The RSA keypair is generated fresh at startup rather than loaded from a
 * persisted keystore. That means outstanding tokens are invalidated on
 * every restart -- acceptable here because client_credentials tokens are
 * short-lived (15 min, see BrokerRegisteredClientRepository) and trivially
 * re-issued; a real production deployment would load a persisted key so a
 * rolling restart doesn't invalidate every in-flight token.
 */
@Configuration
public class AuthorizationServerConfig {

    @Value("${app.oauth2.issuer}")
    private String issuer;

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                http.getConfigurer(OAuth2AuthorizationServerConfigurer.class);

        http
                // /oauth2/token is called with HTTP Basic (client_id:client_secret),
                // not a bearer token -- there's nothing to CSRF-protect on a stateless,
                // non-cookie-based token endpoint.
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServerConfigurer.getEndpointsMatcher()))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                                authorizationServerConfigurer.getEndpointsMatcher()));

        return http.build();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(issuer)
                .build();
    }

    /**
     * Adds broker_id / broker_name claims to every issued access token, so
     * downstream code (ApplicationController) never has to guess at OAuth2
     * convention (whether "sub" happens to equal client_id) -- it reads an
     * explicit, self-documenting claim instead.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (context.getTokenType().getValue().equals("access_token")) {
                String clientId = context.getRegisteredClient().getClientId();
                context.getClaims().claim("broker_id", clientId);
            }
        };
    }

    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        // Explicit rather than relying on Spring Authorization Server's internal
        // default, so it's obvious {noop}/{bcrypt}-prefixed secrets (see
        // Broker.clientSecret's Javadoc) are resolved the same way everywhere.
        return org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = generateRsaKey();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static RSAKey generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not generate RSA keypair for token signing", ex);
        }
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    /**
     * Shared directly from jwkSource() above -- see class Javadoc for why
     * this avoids any HTTP call (even to itself) to validate tokens.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }
}