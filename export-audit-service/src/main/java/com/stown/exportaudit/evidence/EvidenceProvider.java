package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;

import java.util.List;

/**
 * Source of the evidence an export package is built from.
 *
 * <p><b>Assumption:</b> the Case & Hold service is not implemented yet in
 * this repository, so the canonical list of evidence items that belong to a
 * case or a legal-hold scope is not available from a dedicated API. The export
 * service therefore reads the evidence directly from the shared
 * {@code messages} MongoDB collection owned by the ingestion service, applying
 * the optional filters supplied with the export request. When the Case &
 * Hold service ships, swap {@link MongoEvidenceProvider} for an HTTP-backed
 * implementation that calls its evidence API; no other code needs to change
 * because everything downstream depends only on this interface.
 */
public interface EvidenceProvider {

    /**
     * Returns the evidence items that match the supplied criteria, ordered by
     * message timestamp ascending so the export package reads chronologically.
     */
    List<MessageDocument> findEvidence(EvidenceQuery query);
}
