package com.suretyseven.service;

import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.external.ApplicantClient;
import com.suretyseven.external.ApplicantInfo;
import com.suretyseven.external.ExternalApiException;
import com.suretyseven.repository.ApplicationRepository;
import com.suretyseven.service.scoring.ScoringService;
import com.suretyseven.service.scoring.UnderwritingThresholds;
import com.suretyseven.service.scoring.CreditScoreRule;
import com.suretyseven.service.scoring.YearsInBusinessRule;
import com.suretyseven.service.scoring.BondToRevenueRule;
import com.suretyseven.service.scoring.ExistingExposureRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Exercises EvaluationService's success and failure branches directly
 * (bypassing Kafka and HTTP) by mocking ApplicantClient and
 * EvaluationPersistence. This is the fastest place to pin down the
 * failure-handling behaviour the assignment calls out explicitly: what
 * happens on a timeout/5xx.
 *
 * Note evaluate() now THROWS on failure (see its Javadoc) rather than
 * swallowing the exception -- that's what lets
 * ApplicationSubmittedListener's caller (Spring Kafka's error handler)
 * decide whether to retry or dead-letter the message. These tests assert
 * that propagation explicitly.
 */
class EvaluationServiceTest {

    private ApplicationRepository applicationRepository;
    private ApplicantClient applicantClient;
    private EvaluationPersistence persistence;
    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        applicantClient = mock(ApplicantClient.class);
        persistence = mock(EvaluationPersistence.class);
        ScoringService scoringService = new ScoringService(
                List.of(new CreditScoreRule(), new YearsInBusinessRule(), new BondToRevenueRule(), new ExistingExposureRule()),
                new UnderwritingThresholds(80, 50));

        evaluationService = new EvaluationService(applicationRepository, applicantClient, scoringService, persistence, 2);
    }

    private Application submittedApplication() {
        return Application.builder()
                .applicationId("APP-TEST1")
                .brokerId("demo-broker-1")
                .applicantId("COMP-1")
                .bondType("CONTRACT")
                .bondAmount(BigDecimal.valueOf(500_000))
                .effectiveDate(LocalDate.now().plusDays(1))
                .obligeeName("ABC Construction")
                .status(ApplicationStatus.SUBMITTED)
                .attemptCount(0)
                .correlationId("corr-1")
                .build();
    }

    @Test
    void successfulEvaluationFinalizesDecisionAndNeverMarksNeedsAttention() {
        Application app = submittedApplication();
        when(applicationRepository.findByApplicationId("APP-TEST1")).thenReturn(Optional.of(app));
        ApplicantInfo info = new ApplicantInfo("COMP-1", BigDecimal.valueOf(12_000_000), 8, 760, BigDecimal.valueOf(1_500_000));
        when(applicantClient.fetchApplicantAsync(eq("COMP-1"), any())).thenReturn(CompletableFuture.completedFuture(info));

        evaluationService.evaluate("APP-TEST1");

        verify(persistence).markInReview("APP-TEST1");
        verify(persistence).finalizeDecision(eq("APP-TEST1"), eq(info), any());
        verify(persistence, never()).markNeedsAttention(any(), any());
    }

    @Test
    void externalApiFailurePropagatesAsEvaluationFailedException() {
        Application app = submittedApplication();
        when(applicationRepository.findByApplicationId("APP-TEST1")).thenReturn(Optional.of(app));
        CompletableFuture<ApplicantInfo> failed = new CompletableFuture<>();
        failed.completeExceptionally(new ExternalApiException("simulated 500"));
        when(applicantClient.fetchApplicantAsync(eq("COMP-1"), any())).thenReturn(failed);

        assertThatThrownBy(() -> evaluationService.evaluate("APP-TEST1"))
                .isInstanceOf(EvaluationService.EvaluationFailedException.class)
                .hasFieldOrPropertyWithValue("applicationId", "APP-TEST1");

        verify(persistence).markInReview("APP-TEST1");
        verify(persistence, never()).finalizeDecision(any(), any(), any());
        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(persistence).markNeedsAttention(eq("APP-TEST1"), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOfAny(java.util.concurrent.ExecutionException.class, ExternalApiException.class);
    }

    @Test
    void alreadyTerminalApplicationIsNotReEvaluated() {
        Application app = submittedApplication();
        app.setStatus(ApplicationStatus.APPROVED);
        when(applicationRepository.findByApplicationId("APP-TEST1")).thenReturn(Optional.of(app));

        evaluationService.evaluate("APP-TEST1");

        verifyNoInteractions(applicantClient);
        verify(persistence, never()).markInReview(any());
    }

    @Test
    void unknownApplicationIdIsLoggedAndIgnored() {
        when(applicationRepository.findByApplicationId("APP-NOPE")).thenReturn(Optional.empty());

        evaluationService.evaluate("APP-NOPE");

        verifyNoInteractions(applicantClient, persistence);
    }
}
