package com.suretyseven.kafka;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory record of decisioned events the mock downstream "system" has
 * consumed, purely so the demo has something to inspect (GET
 * /mock/downstream/events) and so ApplicationApiIntegrationTest can assert
 * a notification actually arrived. A real downstream team's own service
 * would just consume applications.decisioned directly and do whatever it
 * needs to with each event -- there'd be nothing here to inspect via HTTP
 * at all.
 */
@Component
public class DownstreamEventStore {

    private static final int MAX_KEPT = 50;
    private final ConcurrentLinkedDeque<Map<String, Object>> received = new ConcurrentLinkedDeque<>();

    public void record(Map<String, Object> event) {
        received.addFirst(event);
        while (received.size() > MAX_KEPT) {
            received.removeLast();
        }
    }

    public List<Map<String, Object>> recent() {
        return List.copyOf(received);
    }
}
