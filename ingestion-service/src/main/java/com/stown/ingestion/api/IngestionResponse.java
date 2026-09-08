package com.stown.ingestion.api;

import com.stown.ingestion.domain.IngestionStatus;

/**
 * Acceptance response. The message is stored asynchronously by the ingestion
 * worker, so {@code messageId} is only present once processing has assigned it.
 */
public record IngestionResponse(
        String requestId,
        String deduplicationKey,
        IngestionStatus status,
        boolean duplicate,
        String messageId
) {
}
