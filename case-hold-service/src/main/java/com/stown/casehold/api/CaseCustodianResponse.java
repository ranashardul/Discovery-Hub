package com.stown.casehold.api;

import java.time.Instant;

/**
 * A person attached to a case as a custodian. The identifier is the same
 * identity used in the archive (typically an email address); display names
 * or departments are resolved from the message store when needed.
 */
public record CaseCustodianResponse(
        String custodianId,
        String displayName,
        String email,
        String department,
        Instant addedAt,
        String addedBy
) {
}
