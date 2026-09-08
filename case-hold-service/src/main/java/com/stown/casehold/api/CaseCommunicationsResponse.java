package com.stown.casehold.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The communications associated with a case. {@code added} lists every
 * communication now linked to the case (in insertion order), while
 * {@code newlyAdded} lists only the ones added by the request that produced
 * this response.
 */
public record CaseCommunicationsResponse(
        UUID caseId,
        long total,
        List<CaseCommunicationItem> added,
        List<CaseCommunicationItem> newlyAdded
) {

    public record CaseCommunicationItem(
            String communicationId,
            String communicationType,
            Instant addedAt
    ) {
    }
}
