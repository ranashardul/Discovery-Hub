package com.stown.casehold.repository;

import com.stown.casehold.domain.MessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Read-only access to the {@code messages} collection owned by
 * ingestion-service. This service never writes here.
 *
 * <p>{@code findAllById} from the parent interface is the only operation
 * needed: references are always resolved in batches, which keeps the work to a
 * single round trip even when the store is a remote Atlas cluster.
 */
public interface MessageRepository extends MongoRepository<MessageDocument, String> {
}
