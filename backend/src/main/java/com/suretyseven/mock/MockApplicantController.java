package com.suretyseven.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Stand-in for the external Applicant API described in the assignment.
 *
 * Not part of the "real" service surface -- in a real deployment this
 * would be someone else's system. It's included here so the whole
 * workflow is runnable end to end with `docker compose up` and no
 * external accounts.
 *
 * Behaviour is deterministic per applicantId (same id -> same data) EXCEPT
 * for a simulation hook so failure paths can be exercised on demand:
 *
 *   GET /external/applicants/{id}                -> normal success
 *   GET /external/applicants/{id}?simulate=timeout    -> hangs past any sane client timeout
 *   GET /external/applicants/{id}?simulate=error      -> HTTP 500
 *   GET /external/applicants/{id}?simulate=malformed  -> HTTP 200 with a broken body
 *   GET /external/applicants/{id}?simulate=slow       -> ~3s delay, then a normal response
 *
 * The same modes can also be triggered without a query param by prefixing
 * the applicantId itself, e.g. "TIMEOUT-COMP-1", "ERROR-COMP-1",
 * "MALFORMED-COMP-1", "SLOW-COMP-1" -- handy from the frontend where adding
 * a query param is more friction than just typing a different applicant id.
 */
@RestController
@RequestMapping("/external/applicants")
public class MockApplicantController {

    private static final Logger log = LoggerFactory.getLogger(MockApplicantController.class);

    @GetMapping("/{applicantId}")
    public ResponseEntity<?> getApplicant(@PathVariable String applicantId,
                                           @RequestParam(required = false) String simulate) throws InterruptedException {
        String mode = resolveMode(applicantId, simulate);
        log.info("Mock Applicant API invoked applicantId={} mode={}", applicantId, mode);

        switch (mode) {
            case "timeout" -> {
                Thread.sleep(20_000);
                return ResponseEntity.ok(buildApplicant(applicantId));
            }
            case "error" -> {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error\":\"simulated upstream failure\"}");
            }
            case "malformed" -> {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{ this is not valid json ][");
            }
            case "slow" -> {
                Thread.sleep(3_000);
                return ResponseEntity.ok(buildApplicant(applicantId));
            }
            default -> {
                return ResponseEntity.ok(buildApplicant(applicantId));
            }
        }
    }

    private String resolveMode(String applicantId, String simulate) {
        if (simulate != null && !simulate.isBlank()) {
            return simulate.toLowerCase(Locale.ROOT);
        }
        String upper = applicantId.toUpperCase(Locale.ROOT);
        if (upper.startsWith("TIMEOUT-")) return "timeout";
        if (upper.startsWith("ERROR-")) return "error";
        if (upper.startsWith("MALFORMED-")) return "malformed";
        if (upper.startsWith("SLOW-")) return "slow";
        return "success";
    }

    /** Deterministic pseudo-data derived from a hash of the applicantId, so repeat calls are stable. */
    private Object buildApplicant(String applicantId) {
        int h = Math.abs(applicantId.hashCode());
        int creditScore = 600 + (h % 250);                 // 600-849
        int yearsInBusiness = 1 + (h % 20);                 // 1-20
        long revenue = 500_000L + (h % 20) * 1_000_000L;    // 0.5M-20M
        long exposure = (h % 5) * 500_000L;                 // 0-2M

        return new ApplicantPayload(applicantId, BigDecimal.valueOf(revenue), yearsInBusiness,
                creditScore, BigDecimal.valueOf(exposure));
    }

    private record ApplicantPayload(String applicantId, BigDecimal annualRevenue, Integer yearsInBusiness,
                                     Integer creditScore, BigDecimal existingExposure) {}
}
