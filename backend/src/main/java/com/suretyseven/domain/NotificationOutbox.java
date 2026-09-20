package com.suretyseven.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Transactional outbox row -- for BOTH event types this app publishes to
 * Kafka (eventType "APPLICATION_SUBMITTED" and "APPLICATION_DECISIONED";
 * see kafka.KafkaTopics for where each one ends up).
 *
 * Written in the SAME database transaction as whatever DB change the event
 * describes (a new Application row for SUBMITTED, the terminal
 * status/score/decision for DECISIONED), so "the fact happened" and "an
 * event describing it is queued" either both commit or neither does --
 * there is no window where the DB reflects something nobody was ever told
 * about, even if the process crashes right after commit. A separate
 * scheduled publisher (kafka.KafkaOutboxPublisher) polls PENDING rows and
 * publishes them to the appropriate Kafka topic, independent of the
 * request/evaluation thread that wrote them.
 */
@Entity
@Table(name = "notification_outbox", indexes = {
        @Index(name = "ix_outbox_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, length = 40)
    private String applicationId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
