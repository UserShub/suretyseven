package com.suretyseven.external;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Client for the external Applicant API (GET /external/applicants/{id}).
 *
 * Wrapped with Resilience4j:
 *  - retry: transient failures (timeouts, 5xx) are retried a small number of
 *    times with backoff before giving up
 *  - circuitBreaker: if the external API is having a bad time, stop hammering
 *    it and fail fast for a cooldown window, rather than piling up latency
 *    across every in-flight evaluation
 *  - timeLimiter + a bounded RestClient timeout: a genuinely slow/hanging
 *    response cannot tie up an evaluation thread indefinitely
 *
 * All resilience parameters live in application.yml under
 * resilience4j.* so they can be tuned without a code change.
 */
@Component
public class ApplicantClient {

    private static final Logger log = LoggerFactory.getLogger(ApplicantClient.class);
    private static final String INSTANCE = "applicantApi";

    private final RestClient restClient;
    private final ExecutorService executorService;

    public ApplicantClient(RestClient.Builder builder,
                            @Qualifier("externalCallExecutor") ExecutorService executorService,
                            org.springframework.core.env.Environment env) {
        String baseUrl = env.getProperty("external.applicant-api.base-url", "http://localhost:8080");
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
        this.executorService = executorService;
    }

    @Retry(name = INSTANCE)
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "fallback")
    @TimeLimiter(name = INSTANCE)
    public CompletableFuture<ApplicantInfo> fetchApplicantAsync(String applicantId, String correlationId) {
        return CompletableFuture.supplyAsync(() -> fetch(applicantId, correlationId), executorService);
    }

    private ApplicantInfo fetch(String applicantId, String correlationId) {
        try {
            return restClient.get()
                    .uri("/external/applicants/{id}", applicantId)
                    .header("X-Correlation-Id", correlationId)
                    .retrieve()
                    .body(ApplicantInfo.class);
        } catch (HttpServerErrorException e) {
            log.warn("Applicant API returned {} for applicantId={} correlationId={}",
                    e.getStatusCode(), applicantId, correlationId);
            throw new ExternalApiException("Applicant API server error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            log.warn("Applicant API timeout/connection failure for applicantId={} correlationId={}",
                    applicantId, correlationId);
            throw new ExternalApiException("Applicant API unreachable or timed out", e);
        } catch (Exception e) {
            log.warn("Applicant API returned an unparsable/invalid response for applicantId={} correlationId={}",
                    applicantId, correlationId);
            throw new ExternalApiException("Applicant API returned malformed response", e);
        }
    }

    // Resilience4j fallback signature: same args + Throwable, must match return type
    @SuppressWarnings("unused")
    private CompletableFuture<ApplicantInfo> fallback(String applicantId, String correlationId, Throwable t) {
        log.error("All retries/circuit breaker exhausted for applicantId={} correlationId={} cause={}",
                applicantId, correlationId, t.toString());
        CompletableFuture<ApplicantInfo> failed = new CompletableFuture<>();
        failed.completeExceptionally(new ExternalApiException("Applicant API unavailable after retries", t));
        return failed;
    }
}
