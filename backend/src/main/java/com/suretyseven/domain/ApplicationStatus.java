package com.suretyseven.domain;

/**
 * Lifecycle of a bond application.
 *
 * SUBMITTED         -> persisted, evaluation not started yet
 * IN_REVIEW         -> evaluation in progress (external call + scoring)
 * NEEDS_ATTENTION   -> evaluation could not complete because the external
 *                      Applicant API failed after retries; will be retried
 *                      automatically by the recovery job, up to a limit
 * APPROVED / REFERRED / DECLINED -> terminal states, decision made
 * FAILED            -> evaluation permanently failed after exhausting
 *                      automatic retries; requires manual handling
 */
public enum ApplicationStatus {
    SUBMITTED,
    IN_REVIEW,
    NEEDS_ATTENTION,
    APPROVED,
    REFERRED,
    DECLINED,
    FAILED
}
