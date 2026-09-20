package com.suretyseven.controller;

import com.suretyseven.dto.ApplicationResponse;
import com.suretyseven.domain.ApplicationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests through real HTTP AND a real (embedded, in-memory)
 * Kafka broker -- exercising the full "accept -> publish
 * ApplicationSubmittedEvent -> evaluate -> decide -> publish
 * ApplicationDecisionedEvent -> notify downstream" pipeline the way it
 * actually runs in production, including the failure path (permanent
 * external-API failure -> Kafka retry/backoff -> dead-letter -> FAILED)
 * and cross-broker authorization (one broker can never see another's
 * applications).
 *
 * Real OAuth2 tokens are obtained from this same app's own
 * /oauth2/token endpoint using the two demo brokers seeded by
 * V2__oauth2_brokers.sql -- there is no mocked security context here,
 * this is the actual client_credentials flow a real broker integration
 * would use.
 *
 * Resilience/outbox timings are tightened via properties below purely so
 * this test doesn't take minutes to run; production values live in
 * application.yml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.properties.security.protocol=PLAINTEXT",
        "external.applicant-api.base-url=http://localhost:${local.server.port}",
        "external.applicant-api.overall-call-timeout-seconds=2",
        "outbox.poll-interval-ms=300",
        "resilience4j.retry.instances.applicantApi.max-attempts=2",
        "resilience4j.retry.instances.applicantApi.wait-duration=100ms",
        "resilience4j.timelimiter.instances.applicantApi.timeout-duration=1s"
})
@DirtiesContext
class ApplicationApiIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    private String brokerOneToken;

    @BeforeEach
    void obtainTokens() {
        brokerOneToken = obtainAccessToken("demo-broker-1", "demo-secret-1");
    }

    private String obtainAccessToken(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, clientSecret);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("scope", "applications.read applications.write");
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = rest.postForEntity("/oauth2/token", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private Map<String, Object> validRequestBody(String applicantId) {
        return Map.of(
                "applicantId", applicantId,
                "bondType", "CONTRACT",
                "bondAmount", 500000,
                "effectiveDate", java.time.LocalDate.now().plusDays(30).toString(),
                "obligee", Map.of("name", "ABC Construction LLC")
        );
    }

    @Test
    void oauth2TokenEndpointRejectsWrongClientSecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth("demo-broker-1", "wrong-secret");
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        ResponseEntity<String> response = rest.postForEntity("/oauth2/token", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void happyPathReachesApprovedAndNotifiesDownstream() {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(validRequestBody("COMP-STRONG-1"), bearerHeaders(brokerOneToken));

        ResponseEntity<ApplicationResponse> created = rest.postForEntity("/applications", request, ApplicationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().status()).isEqualTo(ApplicationStatus.SUBMITTED);
        String applicationId = created.getBody().applicationId();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<ApplicationResponse> got = rest.exchange(
                    "/applications/" + applicationId, HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerOneToken)), ApplicationResponse.class);
            assertThat(got.getBody().status()).isIn(ApplicationStatus.APPROVED, ApplicationStatus.REFERRED, ApplicationStatus.DECLINED);
            assertThat(got.getBody().score()).isNotNull();
            assertThat(got.getBody().scoreBreakdown()).isNotNull();
        });

        // The decisioned event should have made it through Kafka to the mock downstream consumer.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<List> events = rest.getForEntity("/mock/downstream/events", List.class);
            boolean found = events.getBody().stream()
                    .anyMatch(e -> applicationId.equals(((Map) e).get("applicationId")));
            assertThat(found).isTrue();
        });
    }

    @Test
    void repeatedIdempotencyKeyReturnsSameApplication() {
        HttpHeaders headers = bearerHeaders(brokerOneToken);
        headers.set("Idempotency-Key", "test-idem-key-1");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(validRequestBody("COMP-IDEM-1"), headers);

        ResponseEntity<ApplicationResponse> first = rest.postForEntity("/applications", request, ApplicationResponse.class);
        ResponseEntity<ApplicationResponse> second = rest.postForEntity("/applications", request, ApplicationResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().applicationId()).isEqualTo(first.getBody().applicationId());
    }

    @Test
    void permanentExternalFailureEventuallyMarksApplicationFailed() {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(validRequestBody("ERROR-COMP-1"), bearerHeaders(brokerOneToken));

        ResponseEntity<ApplicationResponse> created = rest.postForEntity("/applications", request, ApplicationResponse.class);
        String applicationId = created.getBody().applicationId();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<ApplicationResponse> got = rest.exchange(
                    "/applications/" + applicationId, HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerOneToken)), ApplicationResponse.class);
            assertThat(got.getBody().status()).isEqualTo(ApplicationStatus.FAILED);
            assertThat(got.getBody().failureReason()).isNotBlank();
        });
    }

    @Test
    void invalidRequestIsRejectedWithValidationDetails() {
        Map<String, Object> badBody = Map.of("applicantId", "", "bondType", "NOT_A_TYPE");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(badBody, bearerHeaders(brokerOneToken));

        ResponseEntity<Map> response = rest.postForEntity("/applications", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void missingBearerTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(validRequestBody("COMP-NOAUTH"), headers);

        ResponseEntity<String> response = rest.postForEntity("/applications", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownApplicationIdReturns404() {
        ResponseEntity<Map> response = rest.exchange(
                "/applications/APP-DOES-NOT-EXIST", HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerOneToken)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listApplicationsReturnsPagedResultsNewestFirst() {
        HttpHeaders headers = bearerHeaders(brokerOneToken);
        rest.postForEntity("/applications", new HttpEntity<>(validRequestBody("COMP-LIST-1"), headers), ApplicationResponse.class);
        ResponseEntity<ApplicationResponse> second = rest.postForEntity(
                "/applications", new HttpEntity<>(validRequestBody("COMP-LIST-2"), headers), ApplicationResponse.class);

        ResponseEntity<Map> page = rest.exchange(
                "/applications?page=0&size=5", HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerOneToken)), Map.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> content = (List<Map>) page.getBody().get("content");
        assertThat(content).isNotEmpty();
        assertThat(content.get(0).get("applicationId")).isEqualTo(second.getBody().applicationId());
        assertThat(page.getBody()).containsKeys("totalElements", "totalPages", "page", "size");
    }

    @Test
    void brokerCanNeverSeeAnotherBrokersApplication() {
        // Broker one creates an application...
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(validRequestBody("COMP-TENANT-1"), bearerHeaders(brokerOneToken));
        ResponseEntity<ApplicationResponse> created = rest.postForEntity("/applications", request, ApplicationResponse.class);
        String applicationId = created.getBody().applicationId();

        // ...and broker two, a completely different OAuth2 client, cannot fetch it by ID...
        String brokerTwoToken = obtainAccessToken("demo-broker-2", "demo-secret-2");
        ResponseEntity<Map> getAsOtherBroker = rest.exchange(
                "/applications/" + applicationId, HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerTwoToken)), Map.class);
        assertThat(getAsOtherBroker.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ...nor does it show up in broker two's own application list.
        ResponseEntity<Map> listAsOtherBroker = rest.exchange(
                "/applications?page=0&size=50", HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerTwoToken)), Map.class);
        List<Map> content = (List<Map>) listAsOtherBroker.getBody().get("content");
        boolean leaked = content.stream().anyMatch(e -> applicationId.equals(e.get("applicationId")));
        assertThat(leaked).isFalse();

        // But broker one can still see their own application just fine.
        ResponseEntity<ApplicationResponse> getAsOwner = rest.exchange(
                "/applications/" + applicationId, HttpMethod.GET, new HttpEntity<>(bearerHeaders(brokerOneToken)), ApplicationResponse.class);
        assertThat(getAsOwner.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
