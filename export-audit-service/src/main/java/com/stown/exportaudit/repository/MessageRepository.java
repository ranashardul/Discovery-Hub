package com.stown.exportaudit.repository;

import com.stown.exportaudit.domain.MessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Read-only access to the shared {@code messages} collection owned by the
 * ingestion service. The export service never writes here; dynamic evidence
 * queries (optional filters) are performed through {@code MongoTemplate} in
 * the evidence provider, while this repository covers simple lookups.
 */
public interface MessageRepository
        extends MongoRepository<MessageDocument, String> {

    Optional<MessageDocument> findByDeduplicationKey(String deduplicationKey);

    Optional<MessageDocument> findByExternalMessageId(String externalMessageId);
}
