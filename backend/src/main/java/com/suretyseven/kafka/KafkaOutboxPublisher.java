package com.suretyseven.kafka;

import com.suretyseven.domain.NotificationOutbox;
import com.suretyseven.domain.OutboxStatus;
import com.suretyseven.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Drains the transactional outbox (see NotificationOutbox) by publishing
 * each PENDING row to Kafka, instead of the old design's direct HTTP POST
 * to a webhook.
 *
 * The outbox itself is unchanged and still necessary even with Kafka in
 * the picture: writing to Postgres and publishing to Kafka are two
 * different systems, and doing both "atomically" inline (write decision,
 * then immediately kafkaTemplate.send()) would reintroduce exactly the
 * dual-write problem outboxing exists to avoid -- if the process died
 * between those two steps, or the Kafka send failed, the decision would
 * exist with no corresponding event ever published. Instead: the DB write
 * and the outbox row commit together in one transaction (see
 * EvaluationPersistence / ApplicationService), and only this poller,
 * completely decoupled from request/evaluation handling, is responsible
 * for actually getting the row onto Kafka -- retrying with backoff and
 * eventually dead-lettering at the OUTBOX level if Kafka itself is
 * unreachable for a while. This is a different, narrower failure domain
 * than the CONSUMER-side retry/DLT that KafkaConfig sets up for messages
 * that made it onto a topic but failed to process -- two layers, two
 * different things that can go wrong.
 */
@Component
public class KafkaOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private static final int BATCH_SIZE = 20;
    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            "APPLICATION_SUBMITTED", KafkaTopics.APPLICATIONS_SUBMITTED,
            "APPLICATION_DECISIONED", KafkaTopics.APPLICATIONS_DECISIONED
    );

    private final NotificationOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int maxAttempts;

    public KafkaOutboxPublisher(NotificationOutboxRepository outboxRepository,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 org.springframework.core.env.Environment env) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxAttempts = Integer.parseInt(env.getProperty("outbox.max-attempts", "6"));
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}")
    @Transactional
    public void publishDue() {
        List<NotificationOutbox> due = outboxRepository.findDueForDelivery(
                OutboxStatus.PENDING, Instant.now(), PageRequest.of(0, BATCH_SIZE));

        for (NotificationOutbox event : due) {
            publish(event);
        }
    }

    private void publish(NotificationOutbox event) {
        String topic = TOPIC_BY_EVENT_TYPE.get(event.getEventType());
        if (topic == null) {
            log.error("Outbox row id={} has unknown eventType={}, dead-lettering without publishing",
                    event.getId(), event.getEventType());
            event.setStatus(OutboxStatus.DEAD_LETTER);
            event.setLastError("Unknown eventType: " + event.getEventType());
            outboxRepository.save(event);
            return;
        }

        try {
            // applicationId as the Kafka message key: every event for one application
            // lands on the same partition, so a consumer never sees, say, a
            // DECISIONED event processed out of order relative to its own SUBMITTED
            // event (relevant only for consumers that read both topics; today's
            // consumers don't, but it's a cheap guarantee to keep for free).
            kafkaTemplate.send(topic, event.getApplicationId(), event.getPayloadJson()).get();

            event.setStatus(OutboxStatus.SENT);
            outboxRepository.save(event);
            log.info("Published outbox event id={} applicationId={} eventType={} to topic={}",
                    event.getId(), event.getApplicationId(), event.getEventType(), topic);

        } catch (Exception e) {
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setLastError(safeMessage(e));

            if (event.getAttemptCount() >= maxAttempts) {
                event.setStatus(OutboxStatus.DEAD_LETTER);
                log.error("Outbox event id={} applicationId={} moved to DEAD_LETTER after {} attempts, lastError={}",
                        event.getId(), event.getApplicationId(), event.getAttemptCount(), event.getLastError());
            } else {
                long backoffSeconds = (long) Math.pow(2, event.getAttemptCount());
                event.setNextAttemptAt(Instant.now().plusSeconds(Math.min(backoffSeconds, 300)));
                log.warn("Outbox event id={} applicationId={} attempt {} failed to publish to Kafka, retrying at {}, error={}",
                        event.getId(), event.getApplicationId(), event.getAttemptCount(), event.getNextAttemptAt(), event.getLastError());
            }
            outboxRepository.save(event);
        }
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        return msg.length() > 400 ? msg.substring(0, 400) : msg;
    }
}
