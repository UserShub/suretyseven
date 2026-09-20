package com.suretyseven.service;

import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.external.ApplicantClient;
import com.suretyseven.external.ApplicantInfo;
import com.suretyseven.repository.ApplicationRepository;
import com.suretyseven.service.scoring.ScoreResult;
import com.suretyseven.service.scoring.ScoringContext;
import com.suretyseven.service.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs the actual evaluation of one application: call the external
 * Applicant API, score it, decide, persist, and enqueue the downstream
 * notification (via the outbox -- see EvaluationPersistence).
 *
 * Called by ApplicationSubmittedListener (a Kafka consumer), NOT
 * fire-and-forget from an HTTP request thread and NOT annotated @Async --
 * the concurrency and "off the request thread" guarantee now come from
 * Kafka consumer threads instead. This is deliberate: it means retrying a
 * failed evaluation, and recovering from a crash mid-evaluation, are both
 * Kafka's job (consumer redelivery + KafkaConfig's DefaultErrorHandler +
 * dead-letter topic) rather than a hand-rolled DB-polling recovery job.
 * Exceptions are allowed to propagate out of evaluate() on purpose, so
 * Spring Kafka's error handler can see the failure and decide whether to
 * retry the message again or route it to the dead-letter topic.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final Set<ApplicationStatus> TERMINAL = Set.of(
            ApplicationStatus.APPROVED, ApplicationStatus.REFERRED, ApplicationStatus.DECLINED, ApplicationStatus.FAILED);

    private final ApplicationRepository applicationRepository;
    private final ApplicantClient applicantClient;
    private final ScoringService scoringService;
    private final EvaluationPersistence persistence;
    private final int callTimeoutSeconds;

    public EvaluationService(ApplicationRepository applicationRepository,
                              ApplicantClient applicantClient,
                              ScoringService scoringService,
                              EvaluationPersistence persistence,
                              @Value("${external.applicant-api.overall-call-timeout-seconds:6}") int callTimeoutSeconds) {
        this.applicationRepository = applicationRepository;
        this.applicantClient = applicantClient;
        this.scoringService = scoringService;
        this.persistence = persistence;
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    /**
     * @throws RuntimeException if the external call ultimately fails (after
     *         Resilience4j's own retries inside ApplicantClient) -- allowed
     *         to propagate so the calling Kafka listener's error handler can
     *         retry or dead-letter this message. Returns normally (without
     *         throwing) for an unknown or already-terminal applicationId,
     *         since redelivery of an already-fully-processed message is an
     *         expected, harmless case (see class Javadoc), not a failure.
     */
    public void evaluate(String applicationId) {
        Optional<Application> maybeApp = applicationRepository.findByApplicationId(applicationId);
        if (maybeApp.isEmpty()) {
            log.warn("evaluate() called for unknown applicationId={}", applicationId);
            return;
        }
        Application app = maybeApp.get();
        if (TERMINAL.contains(app.getStatus())) {
            log.debug("applicationId={} already in terminal status={}, skipping (safe redelivery no-op)",
                    applicationId, app.getStatus());
            return;
        }

        String correlationId = app.getCorrelationId();
        persistence.markInReview(applicationId);

        try {
            ApplicantInfo info = applicantClient
                    .fetchApplicantAsync(app.getApplicantId(), correlationId)
                    .get(callTimeoutSeconds, TimeUnit.SECONDS);
            ScoreResult result = scoringService.score(new ScoringContext(info, app.getBondAmount()));
            persistence.finalizeDecision(applicationId, info, result);
            log.info("Evaluation complete applicationId={} decision={} score={}",
                    applicationId, result.decision(), result.totalScore());
        } catch (Exception e) {
            persistence.markNeedsAttention(applicationId, e);
            throw new EvaluationFailedException(applicationId, e);
        }
    }

    /** Unchecked wrapper so evaluate()'s throws-clause stays clean; carries the applicationId for logging at the listener. */
    public static class EvaluationFailedException extends RuntimeException {
        public final String applicationId;
        public EvaluationFailedException(String applicationId, Throwable cause) {
            super("Evaluation failed for applicationId=" + applicationId, cause);
            this.applicationId = applicationId;
        }
    }
}
