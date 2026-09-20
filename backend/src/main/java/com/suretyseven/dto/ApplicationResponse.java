package com.suretyseven.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.suretyseven.domain.Application;
import com.suretyseven.domain.Decision;
import com.suretyseven.domain.ApplicationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationResponse(
        String applicationId,
        ApplicationStatus status,
        Integer score,
        Decision decision,
        String applicantId,
        String bondType,
        BigDecimal bondAmount,
        LocalDate effectiveDate,
        String obligeeName,
        Object scoreBreakdown,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static ApplicationResponse from(Application a, Object parsedBreakdown) {
        return new ApplicationResponse(
                a.getApplicationId(),
                a.getStatus(),
                a.getScore(),
                a.getDecision(),
                a.getApplicantId(),
                a.getBondType(),
                a.getBondAmount(),
                a.getEffectiveDate(),
                a.getObligeeName(),
                parsedBreakdown,
                a.getFailureReason(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
