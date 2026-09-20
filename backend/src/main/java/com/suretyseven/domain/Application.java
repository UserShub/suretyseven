package com.suretyseven.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A bond application and everything needed to explain how a decision was
 * reached for it. One row per application; the row IS the state machine
 * (see ApplicationStatus) rather than a separate "current state" pointer,
 * so a crashed/restarted process can resume purely by querying status.
 */
@Entity
@Table(
        name = "applications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_applications_business_id", columnNames = "application_id"),
                @UniqueConstraint(name = "uk_applications_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "ix_applications_status", columnList = "status"),
                @Index(name = "ix_applications_broker_id", columnList = "broker_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing business identifier, e.g. APP-7F3A2C1B. Never expose the numeric PK. */
    @Column(name = "application_id", nullable = false, updatable = false, length = 40)
    private String applicationId;

    /**
     * The OAuth2 client_id of the broker that submitted this application --
     * see Broker's Javadoc for why "broker" and "OAuth2 client" are the
     * same identifier. Every read path (get-by-id, list) filters by this
     * against the caller's authenticated identity; a broker asking for an
     * applicationId that exists but belongs to someone else gets a 404,
     * not a 403, so existence of other tenants' data is never confirmed.
     */
    @Column(name = "broker_id", nullable = false, updatable = false, length = 100)
    private String brokerId;

    /**
     * Client-supplied Idempotency-Key header value. Nullable because it's
     * optional on the request, but unique when present so a retried POST
     * with the same key can never create a second row.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "applicant_id", nullable = false, length = 60)
    private String applicantId;

    @Column(name = "bond_type", nullable = false, length = 40)
    private String bondType;

    @Column(name = "bond_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal bondAmount;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "obligee_name", nullable = false, length = 200)
    private String obligeeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationStatus status;

    @Column(name = "score")
    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 20)
    private Decision decision;

    /**
     * JSON array of the individual rule outcomes that produced the score,
     * e.g. [{"factor":"CREDIT_SCORE","detail":"760 >= 750","points":30}, ...]
     * Stored as text rather than modeled relationally: it is written once,
     * read as a whole for explainability, and never queried by field.
     */
    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    /** Snapshot of the external applicant data used for the decision, for audit/replay. */
    @Column(name = "applicant_snapshot_json", columnDefinition = "TEXT")
    private String applicantSnapshotJson;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** How many evaluation attempts have been made -- for observability; Kafka's
     *  consumer error handler (not this counter) decides when to stop retrying. */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "correlation_id", length = 60)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: guards against concurrent writers racing on the same row
     *  (e.g. an in-process retry racing a Kafka-triggered re-delivery). */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
