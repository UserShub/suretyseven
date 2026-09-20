package com.suretyseven.service.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Order(4)
public class ExistingExposureRule implements ScoringRule {
    private static final BigDecimal THRESHOLD_PCT = new BigDecimal("0.20");

    @Override
    public RuleOutcome evaluate(ScoringContext ctx) {
        BigDecimal revenue = ctx.applicant().annualRevenue();
        BigDecimal exposure = ctx.applicant().existingExposure();
        if (revenue.signum() <= 0) {
            return new RuleOutcome("EXISTING_EXPOSURE", "annualRevenue unavailable/zero, treated conservatively", 5);
        }
        BigDecimal ratio = exposure.divide(revenue, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(THRESHOLD_PCT) < 0) {
            return new RuleOutcome("EXISTING_EXPOSURE",
                    "existingExposure is " + toPercent(ratio) + "% of annualRevenue (< 20%)", 20);
        }
        return new RuleOutcome("EXISTING_EXPOSURE",
                "existingExposure is " + toPercent(ratio) + "% of annualRevenue (>= 20%)", 5);
    }

    private String toPercent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
