package com.suretyseven.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A broker is, literally, an OAuth2 client: brokerRegisteredClientRepository
 * (see security package) reads this table directly to answer Spring
 * Authorization Server's RegisteredClientRepository lookups. There is no
 * separate "oauth2_client" concept layered on top -- keeping brokers and
 * OAuth2 clients as the same row is deliberate: a broker IS its API
 * credential, and every Application row's broker_id is that same clientId,
 * which is what row-level authorization checks against (see
 * ApplicationService).
 *
 * Broker onboarding (creating new rows here) is out of scope for this
 * assignment -- see Flyway V2 migration for how the demo brokers are
 * seeded. A real implementation would have an admin API/console for this,
 * almost certainly requiring a different, higher-privileged credential
 * than the brokers themselves get.
 */
@Entity
@Table(name = "brokers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_brokers_client_id", columnNames = "client_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Broker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broker_name", nullable = false, length = 200)
    private String brokerName;

    /** OAuth2 client_id. Also the value stored in Application.brokerId -- see class Javadoc. */
    @Column(name = "client_id", nullable = false, updatable = false, length = 100)
    private String clientId;

    /**
     * Password-encoder-prefixed secret (e.g. "{bcrypt}$2a$..." or, for
     * seeded demo data only, "{noop}..."), verified by Spring Security's
     * DelegatingPasswordEncoder exactly like a user password would be.
     */
    @Column(name = "client_secret", nullable = false, length = 200)
    private String clientSecret;

    /** Comma-separated OAuth2 scopes, e.g. "applications.read,applications.write". */
    @Column(name = "scopes", nullable = false, length = 300)
    private String scopes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
