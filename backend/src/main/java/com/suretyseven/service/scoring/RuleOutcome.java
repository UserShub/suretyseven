package com.suretyseven.service.scoring;

/** One rule's contribution to the total score, kept for the explainability record. */
public record RuleOutcome(String factor, String detail, int points) {}
