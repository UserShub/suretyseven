package com.suretyseven.external;

import java.math.BigDecimal;

public record ApplicantInfo(
        String applicantId,
        BigDecimal annualRevenue,
        Integer yearsInBusiness,
        Integer creditScore,
        BigDecimal existingExposure
) {}
