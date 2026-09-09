package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default {@link EvidenceProvider} backed by the shared {@code messages}
 * collection owned by the ingestion service.
 *
 * <p>See the interface javadoc for the assumption about the Case & Hold
 * service. This implementation applies the optional filters from the export
 * request and returns matches ordered by {@code messageTimestamp} ascending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoEvidenceProvider implements EvidenceProvider {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<MessageDocument> findEvidence(EvidenceQuery query) {
        Query mongoQuery = new Query().with(
                Sort.by(Sort.Direction.ASC, "messageTimestamp")
        );

        if (query.getCommunicationType() != null && !query.getCommunicationType().isBlank()) {
            mongoQuery.addCriteria(
                    Criteria.where("communicationType").is(query.getCommunicationType())
            );
        }

        if (query.getSender() != null && !query.getSender().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("sender").is(query.getSender()));
        }

        if (query.getThreadId() != null && !query.getThreadId().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("threadId").is(query.getThreadId()));
        }

        if (query.getFromTimestamp() != null || query.getToTimestamp() != null) {
            Criteria timestampCriteria = Criteria.where("messageTimestamp");

            if (query.getFromTimestamp() != null) {
                timestampCriteria = timestampCriteria.gte(query.getFromTimestamp());
            }

            if (query.getToTimestamp() != null) {
                timestampCriteria = timestampCriteria.lte(query.getToTimestamp());
            }

            mongoQuery.addCriteria(timestampCriteria);
        }

        List<MessageDocument> messages = mongoTemplate.find(mongoQuery, MessageDocument.class);

        log.info(
                "Evidence query caseId={} matched {} messages",
                query.getCaseId(),
                messages.size()
        );

        return messages;
    }
}
