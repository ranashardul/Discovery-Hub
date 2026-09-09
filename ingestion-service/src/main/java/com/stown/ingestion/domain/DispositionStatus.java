package com.stown.ingestion.domain;

/**
 * Values written to {@code MessageDocument.dispositionStatus}.
 *
 * <p>Kept as constants rather than an enum field on the document because
 * search-service reads that field as a {@code String} and maps it as an
 * Elasticsearch keyword.
 */
public final class DispositionStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String ON_HOLD = "ON_HOLD";

    private DispositionStatus() {
    }
}
