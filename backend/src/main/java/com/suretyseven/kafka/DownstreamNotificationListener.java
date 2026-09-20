package com.suretyseven.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stands in for whatever external system the assignment's "notify a
 * downstream system when a decision is completed" requirement refers to.
 * In a real deployment, this class doesn't exist in THIS codebase at all
 * -- another team's service consumes applications.decisioned directly.
 */
@Component
public class DownstreamNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DownstreamNotificationListener.class);

    private final DownstreamEventStore store;
    private final ObjectMapper objectMapper;

    public DownstreamNotificationListener(DownstreamEventStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.APPLICATIONS_DECISIONED, groupId = "downstream-notifier")
    public void onApplicationDecisioned(String payload) throws Exception {
        Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        log.info("Downstream consumer received decisioned event: {}", event);
        store.record(event);
    }
}
