package com.suretyseven.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads the broker_id claim (added by AuthorizationServerConfig's
 * OAuth2TokenCustomizer) off a validated JWT. Centralized in one place so
 * "how do we know which broker is calling" has exactly one answer used
 * everywhere, rather than each controller method reaching into the token
 * however seems convenient at the time.
 */
@Component
public class CurrentBroker {

    public String idFrom(Jwt jwt) {
        String brokerId = jwt.getClaimAsString("broker_id");
        if (brokerId == null || brokerId.isBlank()) {
            // Should be unreachable for any token this app issued itself, but
            // fail loudly rather than silently scoping a query to "null".
            throw new IllegalStateException("Authenticated token is missing the required broker_id claim");
        }
        return brokerId;
    }
}
