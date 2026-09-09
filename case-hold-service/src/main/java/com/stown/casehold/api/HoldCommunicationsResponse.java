package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The communications covered by a hold.
 *
 * <p>{@code unresolvedCount} is how many references did not match a message in
 * the store. {@code enforcedCount} is how many of the resolved messages
 * actually carry a hold in the message store, which is the difference between
 * a hold being recorded here and it being enforced on the data.
 */
public record HoldCommunicationsResponse(
        UUID holdId,
        long total,
        long unresolvedCount,
        long enforcedCount,
        List<HoldCommunicationItem> communications
) {

    public record HoldCommunicationItem(
            String communicationId,
            String communicationType,
            Instant createdAt,
            CommunicationDetail message
    ) {
    }
}
