package com.suretyseven.kafka;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Declares topics explicitly (some managed Kafka -- including Aiven's free
 * tier -- disables broker-side auto-topic-creation, so relying on it would
 * work locally against the docker-compose Kafka container and then
 * silently fail in production) and wires one shared retry/dead-letter
 * policy across every @KafkaListener in this app.
 *
 * Deliberately plain String key/value (de)serialization throughout (see
 * application.yml's spring.kafka.* properties), with each listener parsing
 * its own JSON body via ObjectMapper -- rather than Spring Kafka's
 * JsonSerializer/JsonDeserializer with type-mapping headers. That would
 * work too, but ties producer and consumer to matching Java type
 * configuration in a way that's easy to get subtly wrong; a plain JSON
 * string on the wire is the same contract a downstream team's own
 * (non-Java) consumer of applications.decisioned would see anyway.
 *
 * Retry policy: a failed listener invocation is retried in-process 2 times,
 * 3 seconds apart (layered on TOP of Resilience4j's own finer-grained
 * retry inside ApplicantClient, which already tried a few times before the
 * exception ever reached the listener). If still failing after that, the
 * message is published to `<original-topic>.DLT` and the original offset
 * is committed -- so one permanently-broken application can never block
 * the whole partition behind it. See ApplicationSubmittedDeadLetterListener
 * for what happens to the application record at that point.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public org.apache.kafka.clients.admin.NewTopic applicationsSubmittedTopic() {
        return TopicBuilder.name(KafkaTopics.APPLICATIONS_SUBMITTED).partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic applicationsSubmittedDltTopic() {
        return TopicBuilder.name(KafkaTopics.APPLICATIONS_SUBMITTED + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic applicationsDecisionedTopic() {
        return TopicBuilder.name(KafkaTopics.APPLICATIONS_DECISIONED).partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic applicationsDecisionedDltTopic() {
        return TopicBuilder.name(KafkaTopics.APPLICATIONS_DECISIONED + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * Explicitly typed KafkaTemplate<String, String> bean -- deliberately
     * NOT relying on Spring Boot's autoconfigured KafkaTemplate here. That
     * bean's factory method declares its return type as the raw
     * KafkaTemplate<Object, Object> (bound via ProducerFactory<Object,Object>),
     * regardless of which serializer classes application.yml configures at
     * the property level; property-level config only affects runtime
     * serialization, not the declared generic type Spring's dependency
     * injection matches against. Requesting a narrower KafkaTemplate<String,
     * String> elsewhere (KafkaOutboxPublisher, kafkaErrorHandler below) is
     * therefore not guaranteed to resolve against that bean. Declaring our
     * own exactly-typed bean removes the ambiguity entirely -- and Spring
     * Boot's own KafkaTemplate autoconfiguration backs off automatically
     * once any KafkaTemplate bean already exists.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        java.util.Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Auto-detected and applied to Spring Boot's auto-configured listener
     * container factory (any single DefaultErrorHandler/CommonErrorHandler
     * bean is picked up automatically) -- no need to hand-build the
     * container or consumer factory just to attach this. (The consumer
     * side has no equivalent generic-type ambiguity: @KafkaListener resolves
     * its container factory by BEAN NAME, not generic type, so Spring
     * Boot's autoconfigured consumer factory is used safely as-is.)
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> {
                    log.error("Routing message on topic={} key={} to dead-letter topic after exhausting retries, cause={}",
                            record.topic(), record.key(), exception.toString());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });
        FixedBackOff backOff = new FixedBackOff(3000L, 2L); // 2 retries, 3s apart, then dead-letter
        return new DefaultErrorHandler(recoverer, backOff);
    }
}