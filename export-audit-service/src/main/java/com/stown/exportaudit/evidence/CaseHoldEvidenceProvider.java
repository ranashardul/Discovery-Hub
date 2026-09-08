package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.repository.MessageRepository;
import com.stown.exportaudit.service.CaseHoldClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link EvidenceProvider} that resolves the communication IDs belonging to a
 * case or a legal hold through the Case & Hold service API, then fetches the
 * actual messages from the shared {@code messages} MongoDB collection by ID.
 *
 * <p>This is the preferred provider now that the Case & Hold service exists.
 * It honours NFR-1 (no shared database schemas between services) by asking
 * Case & Hold for the authoritative list of evidence IDs rather than reading
 * the Case & Hold database directly.
 *
 * <p>The MongoDB read is still needed because the message content and
 * attachment metadata live in the ingestion service's {@code messages}
 * collection — but this provider only reads the specific IDs that Case & Hold
 * said belong to the case, not the entire corpus.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CaseHoldEvidenceProvider implements EvidenceProvider {

    private final CaseHoldClient caseHoldClient;
    private final MessageRepository messageRepository;

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

        List<MessageDocument> messages = new ArrayList<>();

        for (String id : communicationIds) {
            messageRepository.findById(id).ifPresent(messages::add);
        }

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
