package com.suretyseven.security;

import com.suretyseven.domain.Broker;
import com.suretyseven.repository.BrokerRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BrokerRegisteredClientRepository implements RegisteredClientRepository {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrokerRegisteredClientRepository.class);

    private final BrokerRepository brokerRepository;

    public BrokerRegisteredClientRepository(BrokerRepository brokerRepository) {
        this.brokerRepository = brokerRepository;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // This is NOT a client-registration attempt. ClientSecretAuthenticationProvider
        // calls save() as routine housekeeping after a SUCCESSFUL secret check: if the
        // matched secret's encoding isn't the DelegatingPasswordEncoder's current
        // preferred one (e.g. our seeded "{noop}" demo secrets, vs. its default
        // "{bcrypt}"), it re-encodes the secret and tries to persist the upgrade.
        // Authentication has ALREADY succeeded by the time this is called, so throwing
        // here breaks a valid login for no reason. Deliberate, logged no-op.
        log.debug("Ignoring RegisteredClientRepository.save() for clientId={} "
                        + "(routine secret-encoding-upgrade housekeeping after a successful auth, "
                        + "not a real registration attempt -- brokers are Flyway-managed)",
                registeredClient.getClientId());
    }

    @Override
    public RegisteredClient findById(String id) {
        return brokerRepository.findById(Long.valueOf(id)).map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        Optional<Broker> broker = brokerRepository.findByClientId(clientId);
        return broker.map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(Broker broker) {
        Set<String> scopes = Arrays.stream(broker.getScopes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return RegisteredClient.withId(String.valueOf(broker.getId()))
                .clientId(broker.getClientId())
                .clientSecret(broker.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(s -> s.addAll(scopes))
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();
    }
}