package com.suretyseven.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.event.ApplicationSubmittedEvent;
import com.suretyseven.service.EvaluationPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * The end of the line for an application whose evaluation kept failing:
 * KafkaConfig's error handler gave up retrying and published the original
 * message here. This is what finally marks the application FAILED --
 * replacing the old DB-polling recovery job's attempt-count cap entirely.
 */
@Component
public class ApplicationSubmittedDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSubmittedDeadLetterListener.class);

    private final EvaluationPersistence persistence;
    private final ObjectMapper objectMapper;

    public ApplicationSubmittedDeadLetterListener(EvaluationPersistence persistence, ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.APPLICATIONS_SUBMITTED + ".DLT", groupId = "evaluation-worker-dlt")
    public void onDeadLetter(String payload,
                              @Header(value = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        String reason = "Applicant information could not be retrieved after repeated attempts"
                + (exceptionMessage != null ? ": " + exceptionMessage : ".");
        try {
            ApplicationSubmittedEvent event = objectMapper.readValue(payload, ApplicationSubmittedEvent.class);
            log.error("Dead-lettered applicationId={} reason={}", event.applicationId(), reason);
            persistence.markFailedPermanently(event.applicationId(), reason);
        } catch (Exception e) {
            // Nothing more we can do with an unparsable dead letter than log it loudly
            // for manual inspection -- there's no applicationId to mark FAILED against.
            log.error("Could not parse dead-lettered payload, giving up on it: {}", payload, e);
        }
    }
}
