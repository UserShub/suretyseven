package com.suretyseven.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.domain.NotificationOutbox;
import com.suretyseven.domain.OutboxStatus;
import com.suretyseven.dto.ApplicationResponse;
import com.suretyseven.dto.ApplicationSummaryResponse;
import com.suretyseven.dto.CreateApplicationRequest;
import com.suretyseven.dto.PagedResponse;
import com.suretyseven.event.ApplicationSubmittedEvent;
import com.suretyseven.repository.ApplicationRepository;
import com.suretyseven.repository.NotificationOutboxRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applicationRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public ApplicationService(ApplicationRepository applicationRepository,
                               NotificationOutboxRepository outboxRepository,
                               IdGenerator idGenerator,
                               ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.outboxRepository = outboxRepository;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    public record CreateResult(ApplicationResponse response, boolean alreadyExisted) {}

    /**
     * Creates an application, or -- if idempotencyKey has already been
     * used -- returns the original result instead of creating a duplicate.
     * This is the whole idempotency story for "same application request
     * submitted twice": the unique constraint on idempotency_key is the
     * real guarantee (it holds even under concurrent requests), the
     * up-front lookup is just an optimization to avoid hitting it.
     *
     * Note the idempotency lookup is deliberately NOT scoped by brokerId:
     * an idempotency key is a client-generated value the broker controls,
     * so collisions across brokers are already vanishingly unlikely, and
     * scoping the lookup by broker would require exposing whether another
     * broker's identical key exists at all.
     *
     * brokerId is trusted as-is from the caller (ApplicationController
     * extracts it from a validated JWT's broker_id claim -- see
     * CurrentBroker) and stamped onto the row, which is what makes every
     * later read for this application ownership-scoped.
     */
    @Transactional
    public CreateResult createApplication(CreateApplicationRequest request, String idempotencyKey,
                                           String correlationId, String brokerId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Application> existing = applicationRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent replay for idempotencyKey={}, returning existing applicationId={}",
                        idempotencyKey, existing.get().getApplicationId());
                return new CreateResult(toResponse(existing.get()), true);
            }
        }

        Application application = Application.builder()
                .applicationId(idGenerator.newApplicationId())
                .brokerId(brokerId)
                .idempotencyKey((idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey)
                .applicantId(request.applicantId())
                .bondType(request.bondType())
                .bondAmount(request.bondAmount())
                .effectiveDate(request.effectiveDate())
                .obligeeName(request.obligee().name())
                .status(ApplicationStatus.SUBMITTED)
                .attemptCount(0)
                .correlationId(correlationId)
                .build();

        try {
            application = applicationRepository.save(application);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent identical retry; the winner's row
            // is now visible, so hand that back instead of failing the request.
            if (idempotencyKey != null) {
                Optional<Application> winner = applicationRepository.findByIdempotencyKey(idempotencyKey);
                if (winner.isPresent()) {
                    return new CreateResult(toResponse(winner.get()), true);
                }
            }
            throw e;
        }

        // Enqueue the "start evaluation" event via the SAME transaction that
        // persisted the application -- this is the transactional-outbox
        // pattern applied to the create path too (see NotificationOutbox /
        // KafkaOutboxPublisher). Without this, a crash between "application
        // committed" and "event published" would leave the application stuck
        // in SUBMITTED forever with nothing to ever pick it up; committing
        // both in one transaction makes that impossible.
        String applicationId = application.getApplicationId();
        NotificationOutbox submittedEvent = NotificationOutbox.builder()
                .applicationId(applicationId)
                .eventType("APPLICATION_SUBMITTED")
                .status(OutboxStatus.PENDING)
                .payloadJson(writeJson(new ApplicationSubmittedEvent(applicationId)))
                .build();
        outboxRepository.save(submittedEvent);

        log.info("Created applicationId={} applicantId={} brokerId={} correlationId={}",
                applicationId, request.applicantId(), brokerId, correlationId);

        return new CreateResult(toResponse(application), false);
    }

    /**
     * @throws EntityNotFoundException both when applicationId doesn't exist
     *         at all AND when it belongs to a different broker -- see
     *         Application.brokerId's Javadoc for why those two cases are
     *         deliberately indistinguishable to the caller.
     */
    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(String applicationId, String brokerId) {
        Application application = applicationRepository.findByApplicationIdAndBrokerId(applicationId, brokerId)
                .orElseThrow(() -> new EntityNotFoundException("No application found with id " + applicationId));
        return toResponse(application);
    }

    /**
     * Newest-first paginated listing, scoped to the calling broker's own
     * applications only. Backs the frontend's "Applications" tab.
     * Deliberately returns the lighter ApplicationSummaryResponse (see its
     * Javadoc) rather than the full detail response.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ApplicationSummaryResponse> listApplications(Pageable pageable, String brokerId) {
        Page<Application> page = applicationRepository.findAllByBrokerId(brokerId, pageable);
        return PagedResponse.from(page, ApplicationSummaryResponse::from);
    }

    private ApplicationResponse toResponse(Application a) {
        Object breakdown = null;
        if (a.getScoreBreakdownJson() != null) {
            try {
                breakdown = objectMapper.readValue(a.getScoreBreakdownJson(), Object.class);
            } catch (Exception e) {
                log.warn("Could not parse stored score breakdown for applicationId={}", a.getApplicationId());
            }
        }
        return ApplicationResponse.from(a, breakdown);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}
