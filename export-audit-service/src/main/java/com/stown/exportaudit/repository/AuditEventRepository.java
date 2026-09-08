package com.stown.exportaudit.repository;

import com.stown.exportaudit.domain.AuditEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Append-only audit store. Only insert and read operations are used by the
 * service; there are no update or delete APIs, so historical events cannot be
 * silently overwritten. {@code eventId} is unique, which makes the Kafka
 * consumer idempotent under redelivery.
 */
public interface AuditEventRepository
        extends MongoRepository<AuditEventDocument, String> {

    Optional<AuditEventDocument> findByEventId(String eventId);

    List<AuditEventDocument> findByCaseIdOrderByTimestampDesc(String caseId);

    List<AuditEventDocument> findByTargetIdOrderByTimestampDesc(String targetId);
}
