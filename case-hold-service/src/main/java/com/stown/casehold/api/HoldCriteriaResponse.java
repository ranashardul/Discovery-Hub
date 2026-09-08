package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;

/**
 * Criteria portion of a {@link HoldResponse}. Null for communication-scope
 * holds.
 */
public record HoldCriteriaResponse(
        List<String> participants,
        List<String> communicationTypes,
        Instant dateFrom,
        Instant dateTo
) {
}
