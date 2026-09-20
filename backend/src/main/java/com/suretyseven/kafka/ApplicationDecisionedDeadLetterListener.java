package com.suretyseven.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A permanently-failed downstream notification doesn't need to change
 * anything in OUR database -- the underwriting decision itself was already
 * committed successfully; only telling someone else about it failed. This
 * just logs loudly for manual/ops follow-up, which is the honest answer
 * for a mocked "someone else's system" dependency.
 */
@Component
public class ApplicationDecisionedDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDecisionedDeadLetterListener.class);

    @KafkaListener(topics = KafkaTopics.APPLICATIONS_DECISIONED + ".DLT", groupId = "downstream-notifier-dlt")
    public void onDeadLetter(String payload) {
        log.error("Downstream notification permanently failed to deliver, payload={}. "
                + "The underlying decision was still recorded successfully -- only the notification was lost.", payload);
    }
}
