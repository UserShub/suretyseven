package com.suretyseven.service.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class YearsInBusinessRule implements ScoringRule {
    @Override
    public RuleOutcome evaluate(ScoringContext ctx) {
        int years = ctx.applicant().yearsInBusiness();
        if (years >= 5) {
            return new RuleOutcome("YEARS_IN_BUSINESS", years + " >= 5", 20);
        }
        return new RuleOutcome("YEARS_IN_BUSINESS", years + " < 5", 10);
    }
}
