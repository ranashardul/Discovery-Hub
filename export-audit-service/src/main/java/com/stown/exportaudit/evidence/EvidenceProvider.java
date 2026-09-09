package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;

import java.util.List;

/**
 * Source of the evidence an export package is built from.
 *
 * <p>The canonical list of evidence items that belong to a case or a legal-hold
 * scope is resolved from the Case & Hold service, and the message content for
 * those IDs is fetched from the ingestion service's read API. The export
 * service never reads another service's database directly (NFR-1): it talks to
 * well-defined HTTP APIs owned by the services that own the data.
 */
public interface EvidenceProvider {

    /**
     * Returns the evidence items that match the supplied criteria, ordered by
     * message timestamp ascending so the export package reads chronologically.
     */
    List<MessageDocument> findEvidence(EvidenceQuery query);
}
