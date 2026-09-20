package com.suretyseven.event;

/**
 * Published once evaluation reaches a terminal decision. Consumed by
 * DownstreamNotificationListener, which stands in for whatever external
 * system the assignment's "notify a downstream system" requirement refers
 * to -- in a real deployment this topic is exactly what that team would
 * subscribe to themselves, no HTTP webhook required.
 */
public record ApplicationDecisionedEvent(String applicationId, String decision, int score) {}
