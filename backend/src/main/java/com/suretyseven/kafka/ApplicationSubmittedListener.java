package com.suretyseven.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.event.ApplicationSubmittedEvent;
import com.suretyseven.service.EvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Replaces the old @Async fire-and-forget trigger: this is now the ONLY
 * thing that starts evaluation of a newly-submitted application. See
 * EvaluationService's Javadoc for why exceptions are allowed to propagate
 * out of this method -- KafkaConfig's DefaultErrorHandler is what decides
 * whether to retry or dead-letter, based on that propagation.
 */
@Component
public class ApplicationSubmittedListener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSubmittedListener.class);

    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    public ApplicationSubmittedListener(EvaluationService evaluationService, ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.APPLICATIONS_SUBMITTED, groupId = "evaluation-worker")
    public void onApplicationSubmitted(String payload) {
        ApplicationSubmittedEvent event;
        try {
            event = objectMapper.readValue(payload, ApplicationSubmittedEvent.class);
        } catch (Exception e) {
            // A malformed message can never succeed no matter how many times it's
            // retried -- fail fast so it goes straight to the dead-letter topic
            // rather than burning three retry attempts on something retrying can't fix.
            log.error("Could not parse ApplicationSubmittedEvent payload: {}", payload, e);
            throw new IllegalArgumentException("Malformed ApplicationSubmittedEvent payload", e);
        }
        log.info("Consumed ApplicationSubmittedEvent applicationId={}", event.applicationId());
        evaluationService.evaluate(event.applicationId());
    }
}
