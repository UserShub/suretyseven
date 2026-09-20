package com.suretyseven;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * Scheduling is enabled for KafkaOutboxPublisher (drains the transactional
 * outbox onto Kafka topics). Kafka listener annotation processing
 * (@EnableKafka) picks up every @KafkaListener bean in the kafka package --
 * that's what now runs application evaluation and downstream notification,
 * replacing the old @Async-based fire-and-forget entirely.
 */
@SpringBootApplication
@EnableScheduling
@EnableKafka
@ConfigurationPropertiesScan
public class SuretySevenApplication {
    public static void main(String[] args) {
        SpringApplication.run(SuretySevenApplication.class, args);
    }
}
