package com.stown.casehold.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A named search scoped to a case, with its criteria decoded back to a map. */
public record SavedSearchResponse(
        UUID savedSearchId,
        UUID caseId,
        String name,
        Map<String, Object> criteria,
        String createdBy,
        Instant createdAt
) {
}
