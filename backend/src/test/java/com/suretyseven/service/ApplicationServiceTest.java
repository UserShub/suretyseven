package com.suretyseven.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.domain.NotificationOutbox;
import com.suretyseven.dto.CreateApplicationRequest;
import com.suretyseven.dto.ObligeeDto;
import com.suretyseven.repository.ApplicationRepository;
import com.suretyseven.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers "the same application request is submitted twice" -- the
 * idempotency requirement from the assignment -- at the service layer,
 * including the race where two identical requests both miss the initial
 * lookup and hit the DB's unique constraint at the same time. Also covers
 * that a fresh application enqueues exactly one APPLICATION_SUBMITTED
 * outbox row (the transactional-outbox trigger that replaces the old
 * @Async fire-and-forget call), and that brokerId is stamped onto the row
 * and threaded through reads.
 */
class ApplicationServiceTest {

    private ApplicationRepository applicationRepository;
    private NotificationOutboxRepository outboxRepository;
    private ApplicationService applicationService;

    private static final String BROKER_ID = "demo-broker-1";

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        outboxRepository = mock(NotificationOutboxRepository.class);
        applicationService = new ApplicationService(applicationRepository, outboxRepository, new IdGenerator(), new ObjectMapper());
        when(applicationRepository.save(any(Application.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(NotificationOutbox.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateApplicationRequest sampleRequest() {
        return new CreateApplicationRequest("COMP-123", "CONTRACT", BigDecimal.valueOf(500_000),
                LocalDate.now().plusDays(1), new ObligeeDto("ABC Construction LLC"));
    }

    @Test
    void firstRequestWithIdempotencyKeyCreatesAndEnqueuesSubmittedEvent() {
        when(applicationRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        ApplicationService.CreateResult result =
                applicationService.createApplication(sampleRequest(), "key-1", "corr-1", BROKER_ID);

        assertThat(result.alreadyExisted()).isFalse();
        assertThat(result.response().status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(result.response().applicationId()).startsWith("APP-");

        ArgumentCaptor<Application> savedApp = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(savedApp.capture());
        assertThat(savedApp.getValue().getBrokerId()).isEqualTo(BROKER_ID);

        ArgumentCaptor<NotificationOutbox> outboxCaptor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("APPLICATION_SUBMITTED");
        assertThat(outboxCaptor.getValue().getApplicationId()).isEqualTo(result.response().applicationId());
        assertThat(outboxCaptor.getValue().getPayloadJson()).contains(result.response().applicationId());
    }

    @Test
    void replayedIdempotencyKeyReturnsOriginalWithoutReEnqueuing() {
        Application existing = existingApplication();
        when(applicationRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        ApplicationService.CreateResult result =
                applicationService.createApplication(sampleRequest(), "key-1", "corr-2", BROKER_ID);

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.response().applicationId()).isEqualTo(existing.getApplicationId());
        verify(applicationRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void concurrentDuplicateRequestFallsBackToWinnerOnConstraintViolation() {
        when(applicationRepository.save(any(Application.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        Application winner = existingApplication();
        when(applicationRepository.findByIdempotencyKey("key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        ApplicationService.CreateResult result =
                applicationService.createApplication(sampleRequest(), "key-race", "corr-3", BROKER_ID);

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.response().applicationId()).isEqualTo(winner.getApplicationId());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void requestWithoutIdempotencyKeyAlwaysCreatesNew() {
        ApplicationService.CreateResult result =
                applicationService.createApplication(sampleRequest(), null, "corr-4", BROKER_ID);

        assertThat(result.alreadyExisted()).isFalse();
        verify(applicationRepository, never()).findByIdempotencyKey(any());
        verify(outboxRepository).save(any(NotificationOutbox.class));
    }

    @Test
    void getApplicationIsScopedToOwningBroker() {
        Application app = existingApplication();
        when(applicationRepository.findByApplicationIdAndBrokerId("APP-EXISTING1", BROKER_ID))
                .thenReturn(Optional.of(app));

        assertThat(applicationService.getApplication("APP-EXISTING1", BROKER_ID).applicationId())
                .isEqualTo("APP-EXISTING1");
        verify(applicationRepository).findByApplicationIdAndBrokerId("APP-EXISTING1", BROKER_ID);
    }

    @Test
    void getApplicationForAnotherBrokerIdThrowsNotFound() {
        when(applicationRepository.findByApplicationIdAndBrokerId("APP-EXISTING1", "demo-broker-2"))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> applicationService.getApplication("APP-EXISTING1", "demo-broker-2"));
    }

    private Application existingApplication() {
        Instant now = Instant.now();
        return Application.builder()
                .applicationId("APP-EXISTING1")
                .brokerId(BROKER_ID)
                .idempotencyKey("key-1")
                .applicantId("COMP-123")
                .bondType("CONTRACT")
                .bondAmount(BigDecimal.valueOf(500_000))
                .effectiveDate(LocalDate.now().plusDays(1))
                .obligeeName("ABC Construction LLC")
                .status(ApplicationStatus.APPROVED)
                .score(87)
                .attemptCount(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
