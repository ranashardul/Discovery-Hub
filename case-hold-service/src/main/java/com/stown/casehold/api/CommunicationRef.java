package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A reference to a communication owned by the ingestion/search data layer.
 * Only the identifier (and optionally the type) is carried; the message body
 * is never duplicated into the Case & Hold database.
 */
public record CommunicationRef(
        @NotBlank @Size(max = 255) String communicationId,
        @Size(max = 64) String communicationType
) {
}
