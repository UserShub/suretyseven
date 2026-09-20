package com.suretyseven.service.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class CreditScoreRule implements ScoringRule {
    @Override
    public RuleOutcome evaluate(ScoringContext ctx) {
        int credit = ctx.applicant().creditScore();
        if (credit >= 750) {
            return new RuleOutcome("CREDIT_SCORE", credit + " >= 750", 30);
        } else if (credit >= 700) {
            return new RuleOutcome("CREDIT_SCORE", credit + " in [700,749]", 20);
        } else {
            return new RuleOutcome("CREDIT_SCORE", credit + " < 700", 5);
        }
    }
}
