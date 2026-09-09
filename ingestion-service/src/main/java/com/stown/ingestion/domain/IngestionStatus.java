package com.stown.ingestion.domain;

/**
 * Lifecycle of an ingestion request. A request keeps the same message ID across
 * retries, so a {@code FAILED} request can be reprocessed without creating a
 * new message identity.
 */
public enum IngestionStatus {

    RECEIVED,
    PROCESSING,
    INGESTED,
    FAILED,

    /**
     * The stored message was disposed after its retention expired, or through
     * the delete API. The request is kept so that a re-submission is reported
     * as disposed rather than silently deduplicated against a message that no
     * longer exists.
     */
    DISPOSED
}
