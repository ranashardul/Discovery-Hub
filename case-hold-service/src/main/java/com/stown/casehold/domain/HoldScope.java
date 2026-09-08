package com.stown.casehold.domain;

/**
 * How a hold identifies the communications it preserves.
 *
 * <ul>
 *   <li>{@code COMMUNICATION} — an explicit, caller-supplied list of
 *       communication IDs.</li>
 *   <li>{@code CRITERIA} — a preservation rule (participants, communication
 *       types, date range). The rule is stored on the hold and published in
 *       the {@code HOLD_CREATED} event so the service that owns the message
 *       data can match and preserve the records.</li>
 * </ul>
 */
public enum HoldScope {
    COMMUNICATION,
    CRITERIA
}
