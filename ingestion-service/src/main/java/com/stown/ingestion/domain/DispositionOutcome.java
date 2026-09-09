package com.stown.ingestion.domain;

public enum DispositionOutcome {

    /** Intent recorded; the message may still exist. */
    PENDING,

    /** Message removed from MongoDB; object purge may still be outstanding. */
    DELETED,

    /** Retention had expired but a legal hold prevented deletion. */
    SKIPPED_ON_HOLD,

    /** Deletion or purge failed; retried by the purge sweeper. */
    FAILED
}
