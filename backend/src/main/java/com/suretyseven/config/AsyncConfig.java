package com.suretyseven.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Bounded thread pool backing ApplicantClient's CompletableFuture-based
 * calls to the external Applicant API (needed so Resilience4j's
 * @TimeLimiter has a Future it can time out on). Evaluation itself no
 * longer runs on a dedicated in-process executor -- that concurrency now
 * comes from Kafka consumer threads (see EvaluationService's Javadoc for
 * why), so this class only provides the one executor Kafka's own threading
 * model doesn't replace.
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ExecutorService externalCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("external-call-");
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
