package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
        UUID holdId,
        UUID caseId,
        String name,
        String description,
        String reason,
        String status,
        String scope,
        HoldCriteriaResponse criteria,
        String createdBy,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt,
        long communicationCount
) {
}
