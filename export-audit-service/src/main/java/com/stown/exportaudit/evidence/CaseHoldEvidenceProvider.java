package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.service.CaseHoldClient;
import com.stown.exportaudit.service.IngestionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link EvidenceProvider} that resolves the communication IDs belonging to a
 * case or a legal hold through the Case & Hold service API, then fetches the
 * actual message content from the ingestion service's read API.
 *
 * <p>This is the preferred provider now that the Case & Hold service exists.
 * It honours NFR-1 (no shared database schemas between services) by asking
 * Case & Hold for the authoritative list of evidence IDs and asking the
 * ingestion service for the message content, rather than reading either
 * service's database directly. The export service owns no copy of the
 * {@code messages} collection.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CaseHoldEvidenceProvider implements EvidenceProvider {

    private final CaseHoldClient caseHoldClient;
    private final IngestionClient ingestionClient;

    @Override
    public List<MessageDocument> findEvidence(EvidenceQuery query) {
        List<String> communicationIds;

        if (query.getScope() == ExportScope.LEGAL_HOLD && query.getHoldId() != null
                && !query.getHoldId().isBlank()) {
            communicationIds = caseHoldClient.getHoldCommunicationIds(query.getHoldId());
        } else if (query.getCaseId() != null && !query.getCaseId().isBlank()) {
            communicationIds = caseHoldClient.getCaseCommunicationIds(query.getCaseId());
        } else {
            log.warn(
                    "No caseId or holdId provided for export; returning empty evidence"
            );
            return List.of();
        }

        if (communicationIds.isEmpty()) {
            log.info(
                    "No communications found scope={} caseId={} holdId={}",
                    query.getScope(), query.getCaseId(), query.getHoldId()
            );
            return List.of();
        }

        // Fetch the message content from ingestion in a single batch call so
        // large exports stay efficient (NFR-3) and the export service never
        // reads the ingestion MongoDB collection directly (NFR-1). Copy into a
        // mutable list so the sort below is safe regardless of the client's
        // returned list implementation.
        Set<String> idSet = new HashSet<>(communicationIds);
        List<MessageDocument> messages = new ArrayList<>(ingestionClient.getMessages(idSet));

        // Keep the chronological order the manifest expects.
        messages.sort(Comparator.comparing(
                m -> m.getMessageTimestamp() == null
                        ? Instant.EPOCH
                        : m.getMessageTimestamp()
        ));

        log.info(
                "Resolved {} communication IDs to {} messages for scope={} caseId={} holdId={}",
                communicationIds.size(),
                messages.size(),
                query.getScope(),
                query.getCaseId(),
                query.getHoldId()
        );

        return messages;
    }
}
