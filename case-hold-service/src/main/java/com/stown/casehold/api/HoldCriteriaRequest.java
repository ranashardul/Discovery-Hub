package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;

/**
 * A preservation rule for a criteria-based hold. At least one field must be
 * set, and {@code dateTo} must not precede {@code dateFrom}.
 */
public record HoldCriteriaRequest(
        List<String> participants,
        List<String> communicationTypes,
        Instant dateFrom,
        Instant dateTo
) {

    /** True when at least one criterion has been supplied. */
    public boolean isEmpty() {
        return isBlank(participants) && isBlank(communicationTypes) && dateFrom == null && dateTo == null;
    }

    private static boolean isBlank(List<String> values) {
        return values == null || values.isEmpty();
    }
}
