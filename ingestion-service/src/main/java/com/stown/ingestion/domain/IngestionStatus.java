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
    FAILED
}
