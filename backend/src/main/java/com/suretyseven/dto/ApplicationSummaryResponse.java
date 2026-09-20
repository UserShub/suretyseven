package com.suretyseven.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.suretyseven.domain.Application;
import com.suretyseven.domain.ApplicationStatus;
import com.suretyseven.domain.Decision;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lighter-weight row for the applications list view -- deliberately leaves
 * out scoreBreakdown/applicantSnapshot (only useful once you're looking at
 * one specific application) so listing a page of results doesn't pull and
 * serialize a pile of JSON blobs nobody's about to read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationSummaryResponse(
        String applicationId,
        ApplicationStatus status,
        Integer score,
        Decision decision,
        String applicantId,
        String bondType,
        BigDecimal bondAmount,
        String obligeeName,
        Instant createdAt,
        Instant updatedAt
) {
    public static ApplicationSummaryResponse from(Application a) {
        return new ApplicationSummaryResponse(
                a.getApplicationId(),
                a.getStatus(),
                a.getScore(),
                a.getDecision(),
                a.getApplicantId(),
                a.getBondType(),
                a.getBondAmount(),
                a.getObligeeName(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
