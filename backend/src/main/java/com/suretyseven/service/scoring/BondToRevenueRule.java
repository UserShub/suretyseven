package com.suretyseven.service.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Order(3)
public class BondToRevenueRule implements ScoringRule {
    private static final BigDecimal THRESHOLD_PCT = new BigDecimal("0.10");

    @Override
    public RuleOutcome evaluate(ScoringContext ctx) {
        BigDecimal revenue = ctx.applicant().annualRevenue();
        BigDecimal bondAmount = ctx.bondAmount();
        if (revenue.signum() <= 0) {
            return new RuleOutcome("BOND_TO_REVENUE", "annualRevenue unavailable/zero, treated conservatively", 10);
        }
        BigDecimal ratio = bondAmount.divide(revenue, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(THRESHOLD_PCT) <= 0) {
            return new RuleOutcome("BOND_TO_REVENUE",
                    "bondAmount is " + toPercent(ratio) + "% of annualRevenue (<= 10%)", 30);
        }
        return new RuleOutcome("BOND_TO_REVENUE",
                "bondAmount is " + toPercent(ratio) + "% of annualRevenue (> 10%)", 10);
    }

    private String toPercent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
