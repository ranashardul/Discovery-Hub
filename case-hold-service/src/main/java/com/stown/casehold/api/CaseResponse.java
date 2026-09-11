package com.stown.casehold.api;

import java.time.Instant;
import java.util.UUID;

public record CaseResponse(
        UUID caseId,
        String caseName,
        String description,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long communicationCount,
        long custodianCount,
        long activeHoldCount
) {
}
