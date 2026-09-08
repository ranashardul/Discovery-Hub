package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldCommunicationsResponse(
        UUID holdId,
        long total,
        List<HoldCommunicationItem> communications
) {

    public record HoldCommunicationItem(
            String communicationId,
            String communicationType,
            Instant createdAt
    ) {
    }
}
