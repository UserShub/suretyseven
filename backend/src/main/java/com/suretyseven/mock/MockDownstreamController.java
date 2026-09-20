package com.suretyseven.mock;

import com.suretyseven.kafka.DownstreamEventStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only inspection window onto what the mock downstream Kafka consumer
 * (see kafka.DownstreamNotificationListener) has received -- there is no
 * longer a POST endpoint here, because nothing calls this over HTTP
 * anymore: downstream notification is now a Kafka topic
 * (applications.decisioned), not a webhook. This endpoint exists purely so
 * the demo and its tests have something to assert against.
 */
@RestController
public class MockDownstreamController {

    private final DownstreamEventStore store;

    public MockDownstreamController(DownstreamEventStore store) {
        this.store = store;
    }

    @GetMapping("/mock/downstream/events")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(store.recent());
    }
}
