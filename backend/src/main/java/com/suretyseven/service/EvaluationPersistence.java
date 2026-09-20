package com.suretyseven.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.domain.Decision;
import com.suretyseven.domain.NotificationOutbox;
import com.suretyseven.domain.OutboxStatus;
import com.suretyseven.event.ApplicationDecisionedEvent;
import com.suretyseven.external.ApplicantInfo;
import com.suretyseven.repository.ApplicationRepository;
import com.suretyseven.repository.NotificationOutboxRepository;
import com.suretyseven.service.scoring.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * All the DB writes EvaluationService (and the Kafka listeners around it)
 * need, each in its own transaction.
 *
 * Pulled into a separate Spring bean (rather than being methods on
 * EvaluationService itself) specifically so @Transactional actually takes
 * effect: Spring's transaction advice is proxy-based, and a method calling
 * another @Transactional method on `this` bypasses the proxy entirely.
 * finalizeDecision() in particular MUST run as one real transaction --
 * it's what makes the outbox pattern atomic (decision + outbox row commit
 * together, or neither does).
 */
@Component
public class EvaluationPersistence {

    private static final Logger log = LoggerFactory.getLogger(EvaluationPersistence.class);

    private final ApplicationRepository applicationRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public EvaluationPersistence(ApplicationRepository applicationRepository,
                                  NotificationOutboxRepository outboxRepository,
                                  ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void markInReview(String applicationId) {
        Application app = require(applicationId);
        app.setStatus(ApplicationStatus.IN_REVIEW);
        app.setAttemptCount(app.getAttemptCount() + 1);
        applicationRepository.save(app);
    }

    @Transactional
    public void finalizeDecision(String applicationId, ApplicantInfo info, ScoreResult result) {
        Application app = require(applicationId);

        app.setScore(result.totalScore());
        app.setDecision(result.decision());
        app.setStatus(statusFor(result.decision()));
        app.setFailureReason(null);
        app.setScoreBreakdownJson(writeJson(result.breakdown()));
        app.setApplicantSnapshotJson(writeJson(info));
        applicationRepository.save(app);

        NotificationOutbox outbox = NotificationOutbox.builder()
                .applicationId(app.getApplicationId())
                .eventType("APPLICATION_DECISIONED")
                .status(OutboxStatus.PENDING)
                .payloadJson(writeJson(new ApplicationDecisionedEvent(
                        app.getApplicationId(), result.decision().name(), result.totalScore())))
                .build();
        outboxRepository.save(outbox);
    }

    /**
     * Transient failure state: set right before EvaluationService rethrows,
     * so the UI/API can show "this is being retried" while Kafka decides
     * (via its retry/backoff, see KafkaConfig) whether to try evaluation
     * again or give up and dead-letter the message. This method does NOT
     * decide give-up-or-retry -- that's Kafka's job, not the database's.
     */
    @Transactional
    public void markNeedsAttention(String applicationId, Throwable cause) {
        Application app = require(applicationId);
        app.setStatus(ApplicationStatus.NEEDS_ATTENTION);
        app.setFailureReason("Applicant information could not be retrieved (attempt " + app.getAttemptCount() + ").");
        applicationRepository.save(app);
        log.warn("applicationId={} evaluation attempt {} failed, cause={}",
                applicationId, app.getAttemptCount(), cause.toString());
    }

    /**
     * Called once by ApplicationSubmittedDeadLetterListener when Kafka has
     * given up retrying entirely (message routed to the .DLT topic) --
     * this is the only path that marks an application FAILED, replacing
     * the old DB-polling recovery job's attempt-count cap.
     */
    @Transactional
    public void markFailedPermanently(String applicationId, String reason) {
        Application app = require(applicationId);
        if (app.getStatus() == ApplicationStatus.APPROVED || app.getStatus() == ApplicationStatus.REFERRED
                || app.getStatus() == ApplicationStatus.DECLINED) {
            // A later retry actually succeeded before the DLT message was processed
            // (possible with in-process retry timing) -- don't clobber a real decision.
            log.info("applicationId={} already reached a decision; ignoring stale dead-letter", applicationId);
            return;
        }
        app.setStatus(ApplicationStatus.FAILED);
        app.setFailureReason(reason);
        applicationRepository.save(app);
        log.error("applicationId={} marked FAILED permanently: {}", applicationId, reason);
    }

    private Application require(String applicationId) {
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalStateException("Application not found: " + applicationId));
    }

    private ApplicationStatus statusFor(Decision decision) {
        return switch (decision) {
            case APPROVE -> ApplicationStatus.APPROVED;
            case REFER -> ApplicationStatus.REFERRED;
            case DECLINE -> ApplicationStatus.DECLINED;
        };
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.error("Failed to serialize evaluation detail to JSON: {}", e.toString());
            return "{}";
        }
    }
}
