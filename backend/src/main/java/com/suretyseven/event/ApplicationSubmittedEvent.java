package com.suretyseven.event;

/**
 * Published (via the transactional outbox -- see NotificationOutbox) the
 * moment an application is persisted as SUBMITTED, and consumed by
 * ApplicationSubmittedListener to actually run evaluation. This is what
 * replaces the old in-process @Async fire-and-forget: creating an
 * application no longer directly invokes any evaluation code at all: it
 * just commits a fact ("this application was submitted") and a worker,
 * anywhere, eventually acts on it.
 */
public record ApplicationSubmittedEvent(String applicationId) {}
