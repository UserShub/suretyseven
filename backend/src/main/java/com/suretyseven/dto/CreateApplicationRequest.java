package com.suretyseven.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateApplicationRequest(
        @NotBlank(message = "applicantId is required")
        @Size(max = 60)
        String applicantId,

        @NotBlank(message = "bondType is required")
        @Pattern(regexp = "CONTRACT|COMMERCIAL|COURT|LICENSE_AND_PERMIT",
                message = "bondType must be one of CONTRACT, COMMERCIAL, COURT, LICENSE_AND_PERMIT")
        String bondType,

        @NotNull(message = "bondAmount is required")
        @DecimalMin(value = "1", message = "bondAmount must be positive")
        @Digits(integer = 13, fraction = 2)
        BigDecimal bondAmount,

        @NotNull(message = "effectiveDate is required")
        @FutureOrPresent(message = "effectiveDate cannot be in the past")
        LocalDate effectiveDate,

        @NotNull(message = "obligee is required")
        @Valid
        ObligeeDto obligee
) {}
