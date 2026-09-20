package com.suretyseven.controller;

import com.suretyseven.common.CorrelationIdFilter;
import com.suretyseven.dto.ApplicationResponse;
import com.suretyseven.dto.ApplicationSummaryResponse;
import com.suretyseven.dto.CreateApplicationRequest;
import com.suretyseven.dto.PagedResponse;
import com.suretyseven.security.CurrentBroker;
import com.suretyseven.service.ApplicationService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final CurrentBroker currentBroker;

    public ApplicationController(ApplicationService applicationService, CurrentBroker currentBroker) {
        this.applicationService = applicationService;
        this.currentBroker = currentBroker;
    }

    /**
     * Create a bond application.
     *
     * Requires the applications.write scope (see ResourceServerConfig) --
     * the calling broker's identity comes from the validated bearer
     * token's broker_id claim, not from anything the request body
     * supplies, and is stamped onto the new row so every later read of it
     * is ownership-scoped to that same broker.
     *
     * Clients that may retry (any broker-facing integration should assume
     * they might) should send an Idempotency-Key header. A retried request
     * with the same key returns the original application (200) instead of
     * creating a duplicate (201). Without a key, each POST creates a new
     * application -- the header is a client opt-in, mirroring how Stripe
     * and similar APIs handle this.
     */
    @PostMapping
    public ResponseEntity<ApplicationResponse> createApplication(
            @Valid @RequestBody CreateApplicationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        String brokerId = currentBroker.idFrom(jwt);
        ApplicationService.CreateResult result =
                applicationService.createApplication(request, idempotencyKey, correlationId, brokerId);

        HttpStatus status = result.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (!result.alreadyExisted()) {
            builder.location(URI.create("/applications/" + result.response().applicationId()));
        }
        return builder.body(result.response());
    }

    /** Requires the applications.read scope. Returns 404 for another broker's applicationId -- see ApplicationService. */
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationResponse> getApplication(@PathVariable String applicationId,
                                                                @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(applicationService.getApplication(applicationId, currentBroker.idFrom(jwt)));
    }

    /**
     * Paginated listing, newest first by default, scoped to the calling
     * broker's own applications only. Backs the frontend's "Applications"
     * tab. Requires the applications.read scope.
     *
     * Examples:
     *   GET /applications                          -> first page, 20/page, newest first
     *   GET /applications?page=2&size=10            -> page 2 (0-indexed), 10/page
     *   GET /applications?sort=score,desc           -> sorted by score instead
     */
    @GetMapping
    public ResponseEntity<PagedResponse<ApplicationSummaryResponse>> listApplications(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(applicationService.listApplications(pageable, currentBroker.idFrom(jwt)));
    }
}
