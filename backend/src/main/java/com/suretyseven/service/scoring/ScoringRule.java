package com.suretyseven.service.scoring;

/**
 * One independently-pluggable scoring rule.
 *
 * Each rule is its own Spring bean implementing this interface, and
 * ScoringService just sums whatever rules are on the classpath (see
 * ScoringService). Adding, removing, or re-weighting a rule is a matter of
 * adding/editing one small class -- it never requires touching the
 * scoring/decision orchestration itself, which is the "should be
 * implemented so they can reasonably be changed later" requirement from
 * the assignment.
 */
public interface ScoringRule {
    RuleOutcome evaluate(ScoringContext context);
}
