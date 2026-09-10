package com.stown.ingestion.api;

/**
 * The effective retention period for one communication type.
 *
 * <p>{@code retentionPeriod} is an ISO-8601 duration, the same form the
 * configuration uses. {@code retentionMinutes} is the same value in minutes,
 * because a UI needs a number to put in a field and parsing durations in the
 * browser is a second place to get it wrong.
 */
public record RetentionPolicyResponse(
        String communicationType,
        String retentionPeriod,
        long retentionMinutes
) {
}
