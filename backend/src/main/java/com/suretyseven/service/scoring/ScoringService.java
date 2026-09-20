package com.suretyseven.service.scoring;

import com.suretyseven.domain.Decision;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sums whatever ScoringRule beans exist and maps the total to a decision.
 *
 * Thresholds are configurable (see application.yml -> underwriting.thresholds)
 * via UnderwritingThresholds rather than hardcoded, for the same
 * "changeable later" reason the rules themselves are pluggable.
 */
@Service
public class ScoringService {

    private final List<ScoringRule> rules;
    private final UnderwritingThresholds thresholds;

    public ScoringService(List<ScoringRule> rules, UnderwritingThresholds thresholds) {
        this.rules = rules;
        this.thresholds = thresholds;
    }

    public ScoreResult score(ScoringContext context) {
        List<RuleOutcome> outcomes = rules.stream().map(r -> r.evaluate(context)).toList();
        int total = outcomes.stream().mapToInt(RuleOutcome::points).sum();
        Decision decision = decide(total);
        return new ScoreResult(total, decision, outcomes);
    }

    private Decision decide(int total) {
        if (total >= thresholds.approveAt()) return Decision.APPROVE;
        if (total >= thresholds.referAt()) return Decision.REFER;
        return Decision.DECLINE;
    }
}
