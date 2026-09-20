package com.suretyseven.service.scoring;

import com.suretyseven.domain.Decision;

import java.util.List;

public record ScoreResult(int totalScore, Decision decision, List<RuleOutcome> breakdown) {}
